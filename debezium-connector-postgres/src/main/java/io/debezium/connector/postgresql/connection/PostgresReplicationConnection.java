/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql.connection;

import static java.lang.Math.toIntExact;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.kafka.connect.errors.ConnectException;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.Configuration;
import io.debezium.connector.postgresql.PostgresConnectorConfig;
import io.debezium.connector.postgresql.TypeRegistry;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.jdbc.JdbcConnectionException;
import io.debezium.util.Clock;
import io.debezium.util.Metronome;

/**
 * Implementation of a {@link ReplicationConnection} for Postgresql. Note that replication connections in PG cannot execute
 * regular statements but only a limited number of replication-related commands.
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
public class PostgresReplicationConnection extends JdbcConnection implements ReplicationConnection {

    private static Logger LOGGER = LoggerFactory.getLogger(PostgresReplicationConnection.class);

    private final String slotName;
    private final PostgresConnectorConfig.LogicalDecoder plugin;
    private final boolean dropSlotOnClose;
    private final Configuration originalConfig;
    private final Duration statusUpdateInterval;
    private final MessageDecoder messageDecoder;
    private final TypeRegistry typeRegistry;
    private final Properties streamParams;

    private long defaultStartingPos;

    /**
     * Creates a new replication connection with the given params.
     *
     * @param config the JDBC configuration for the connection; may not be null
     * @param slotName the name of the DB slot for logical replication; may not be null
     * @param plugin decoder matching the server side plug-in used for streaming changes; may not be null
     * @param dropSlotOnClose whether the replication slot should be dropped once the connection is closed
     * @param statusUpdateInterval the interval at which the replication connection should periodically send status
     * @param typeRegistry registry with PostgreSQL types
     *
     * updates to the server
     */
    private PostgresReplicationConnection(Configuration config,
                                         String slotName,
                                         PostgresConnectorConfig.LogicalDecoder plugin,
                                         boolean dropSlotOnClose,
                                         Duration statusUpdateInterval,
                                         TypeRegistry typeRegistry,
                                         Properties streamParams) {
        super(config, PostgresConnection.FACTORY, null, PostgresReplicationConnection :: defaultSettings);

        this.originalConfig = config;
        this.slotName = slotName;
        this.plugin = plugin;
        this.dropSlotOnClose = dropSlotOnClose;
        this.statusUpdateInterval = statusUpdateInterval;
        this.messageDecoder = plugin.messageDecoder();
        this.typeRegistry = typeRegistry;
        this.streamParams = streamParams;

        try {
            initReplicationSlot();
        }
        catch (ConnectException e) {
            close();
            throw e;
        }
        catch (Throwable t) {
            close();
            throw new ConnectException("Cannot create replication connection", t);
        }
    }

    private void initReplicationSlot() throws SQLException, InterruptedException {
        final String postgresPluginName = plugin.getPostgresPluginName();
        ServerInfo.ReplicationSlot slotInfo;
        try (PostgresConnection connection = new PostgresConnection(originalConfig)) {
            slotInfo = connection.readReplicationSlotInfo(slotName, postgresPluginName);
        }

        boolean shouldCreateSlot = ServerInfo.ReplicationSlot.INVALID == slotInfo;
        try {
            // there's no info for this plugin and slot so create a new slot
            if (shouldCreateSlot) {
                LOGGER.debug("Creating new replication slot '{}' for plugin '{}'", slotName, plugin);

                // creating a temporary slot if it should be dropped an we're on 10 or newer;
                // this is not supported through the API yet
                // see https://github.com/pgjdbc/pgjdbc/issues/1305
                if (useTemporarySlot()) {
                    try (Statement stmt = pgConnection().createStatement()) {
                        stmt.execute(String.format(
                                "CREATE_REPLICATION_SLOT %s TEMPORARY LOGICAL %s",
                                slotName,
                                postgresPluginName
                        ));
                    }
                }
                else {
                    pgConnection().getReplicationAPI()
                        .createReplicationSlot()
                        .logical()
                        .withSlotName(slotName)
                        .withOutputPlugin(postgresPluginName)
                        .make();
                }
            }
            else if (slotInfo.active()) {
                LOGGER.error(
                        "A logical replication slot named '{}' for plugin '{}' and database '{}' is already active on the server." +
                        "You cannot have multiple slots with the same name active for the same database",
                        slotName, postgresPluginName, database());
                throw new IllegalStateException();
            }

            AtomicLong xlogStart = new AtomicLong();
            // replication connection does not support parsing of SQL statements so we need to create
            // the connection without executing on connect statements - see JDBC opt preferQueryMode=simple
            pgConnection();
            execute(statement -> {
                String identifySystemStatement = "IDENTIFY_SYSTEM";
                LOGGER.debug("running '{}' to validate replication connection", identifySystemStatement);
                try (ResultSet rs = statement.executeQuery(identifySystemStatement)) {
                    if (!rs.next()) {
                        throw new IllegalStateException("The DB connection is not a valid replication connection");
                    }
                    String xlogpos = rs.getString("xlogpos");
                    LOGGER.debug("received latest xlogpos '{}'", xlogpos);
                    xlogStart.compareAndSet(0, LogSequenceNumber.valueOf(xlogpos).asLong());
                }
            });

            if (shouldCreateSlot || !slotInfo.hasValidFlushedLsn()) {
                // this is a new slot or we weren't able to read a valid flush LSN pos, so we always start from the xlog pos that was reported
                this.defaultStartingPos = xlogStart.get();
            } else {
                Long latestFlushedLsn = slotInfo.latestFlushedLsn();
                this.defaultStartingPos = latestFlushedLsn < xlogStart.get() ? latestFlushedLsn : xlogStart.get();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("found previous flushed LSN '{}'", ReplicationConnection.format(latestFlushedLsn));
                }
            }
        } catch (SQLException e) {
            throw new JdbcConnectionException(e);
        }
    }

    private boolean useTemporarySlot() throws SQLException {
        return dropSlotOnClose && pgConnection().getServerMajorVersion() >= 10;
    }

    @Override
    public ReplicationStream startStreaming() throws SQLException, InterruptedException {
        return startStreaming(defaultStartingPos);
    }

    @Override
    public ReplicationStream startStreaming(Long offset) throws SQLException, InterruptedException {
        connect();
        if (offset == null || offset <= 0) {
            offset = defaultStartingPos;
        }
        LogSequenceNumber lsn = LogSequenceNumber.valueOf(offset);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("starting streaming from LSN '{}'", lsn.asString());
        }
        return createReplicationStream(lsn);
    }

    protected PgConnection pgConnection() throws SQLException {
        return (PgConnection) connection(false);
    }

    private ReplicationStream createReplicationStream(final LogSequenceNumber lsn) throws SQLException, InterruptedException {
        PGReplicationStream s;

        try {
            try {
                s = startPgReplicationStream(lsn,
                        plugin.forceRds()
                                ? x -> messageDecoder.optionsWithoutMetadata(messageDecoder.tryOnceOptions(x))
                                : x -> messageDecoder.optionsWithMetadata(messageDecoder.tryOnceOptions(x)));
                messageDecoder.setContainsMetadata(plugin.forceRds() ? false : true);
            }
            catch (PSQLException e) {
                LOGGER.debug("Could not register for streaming, retrying without optional options", e);

                if (useTemporarySlot()) {
                    initReplicationSlot();
                }

                s = startPgReplicationStream(lsn, plugin.forceRds() ? messageDecoder::optionsWithoutMetadata : messageDecoder::optionsWithMetadata);
                messageDecoder.setContainsMetadata(plugin.forceRds() ? false : true);
            }
        }
        catch (PSQLException e) {
            if (e.getMessage().matches("(?s)ERROR: option .* is unknown.*")) {
                // It is possible we are connecting to an old wal2json plug-in
                LOGGER.warn("Could not register for streaming with metadata in messages, falling back to messages without metadata");

                if (useTemporarySlot()) {
                    initReplicationSlot();
                }

                s = startPgReplicationStream(lsn, messageDecoder::optionsWithoutMetadata);
                messageDecoder.setContainsMetadata(false);
            }
            else if (e.getMessage().matches("(?s)ERROR: requested WAL segment .* has already been removed.*")) {
                LOGGER.error("Cannot rewind to last processed WAL position", e);
                throw new ConnectException("The offset to start reading from has been removed from the database write-ahead log. Create a new snapshot and consider setting of PostgreSQL parameter wal_keep_segments = 0.");
            }
            else {
                throw e;
            }
        }

        final PGReplicationStream stream = s;

        // the LSN where the replication streams starts from
        final long startingLsn = lsn.asLong();

        return new ReplicationStream() {

            private static final int CHECK_WARNINGS_AFTER_COUNT = 100;
            private int warningCheckCounter = CHECK_WARNINGS_AFTER_COUNT;
            private ExecutorService keepAliveExecutor = null;
            private AtomicBoolean keepAliveRunning;
            private final Metronome metronome = Metronome.sleeper(statusUpdateInterval, Clock.SYSTEM);

            // make sure this is volatile since multiple threads may be interested in this value
            private volatile LogSequenceNumber lastReceivedLsn;

            @Override
            public void read(ReplicationMessageProcessor processor) throws SQLException, InterruptedException {
                ByteBuffer read = stream.read();
                // the lsn we started from is inclusive, so we need to avoid sending back the same message twice
                if (startingLsn >= stream.getLastReceiveLSN().asLong()) {
                    return;
                }
                deserializeMessages(read, processor);
            }

            @Override
            public boolean readPending(ReplicationMessageProcessor processor) throws SQLException, InterruptedException {
                ByteBuffer read = stream.readPending();
                // the lsn we started from is inclusive, so we need to avoid sending back the same message twice
                if (read == null || startingLsn >= stream.getLastReceiveLSN().asLong()) {
                    return false;
                }
                deserializeMessages(read, processor);
                return true;
            }

            private void deserializeMessages(ByteBuffer buffer, ReplicationMessageProcessor processor) throws SQLException, InterruptedException {
                lastReceivedLsn = stream.getLastReceiveLSN();
                messageDecoder.processMessage(buffer, processor, typeRegistry);
            }

            @Override
            public void close() throws SQLException {
                processWarnings(true);
                stream.close();
            }

            @Override
            public void flushLsn(long lsn) throws SQLException {
                doFlushLsn(LogSequenceNumber.valueOf(lsn));
            }

            private void doFlushLsn(LogSequenceNumber lsn) throws SQLException {
                stream.setFlushedLSN(lsn);
                stream.setAppliedLSN(lsn);

                stream.forceUpdateStatus();
            }

            @Override
            public Long lastReceivedLsn() {
                return lastReceivedLsn != null ? lastReceivedLsn.asLong() : null;
            }

            @Override
            public void startKeepAlive(ExecutorService service) {
                if (keepAliveExecutor == null) {
                    keepAliveExecutor = service;
                    keepAliveRunning = new AtomicBoolean(true);
                    keepAliveExecutor.submit(() -> {
                        while (keepAliveRunning.get()) {
                            try {
                                LOGGER.trace("Forcing status update with replication stream");
                                stream.forceUpdateStatus();

                                metronome.pause();
                            }
                            catch (Exception exp) {
                                throw new RuntimeException("received unexpected exception will perform keep alive", exp);
                            }
                        }
                    });
                }
            }

            @Override
            public void stopKeepAlive() {
                if (keepAliveExecutor != null) {
                    keepAliveRunning.set(false);
                    keepAliveExecutor.shutdownNow();
                    keepAliveExecutor = null;
                }
            }

            private void processWarnings(final boolean forced) throws SQLException {
                if (--warningCheckCounter == 0 || forced) {
                    warningCheckCounter = CHECK_WARNINGS_AFTER_COUNT;
                    for (SQLWarning w = connection().getWarnings(); w != null; w = w.getNextWarning()) {
                        LOGGER.debug("Server-side message: '{}', state = {}, code = {}",
                                w.getMessage(), w.getSQLState(), w.getErrorCode());
                    }
                }
            }
        };
    }

    private PGReplicationStream startPgReplicationStream(final LogSequenceNumber lsn, Function<ChainedLogicalStreamBuilder, ChainedLogicalStreamBuilder> configurator) throws SQLException {
        assert lsn != null;
        ChainedLogicalStreamBuilder streamBuilder = pgConnection()
                .getReplicationAPI()
                .replicationStream()
                .logical()
                .withSlotName(slotName)
                .withStartPosition(lsn)
                .withSlotOptions(streamParams);
        streamBuilder = configurator.apply(streamBuilder);

        if (statusUpdateInterval != null && statusUpdateInterval.toMillis() > 0) {
            streamBuilder.withStatusInterval(toIntExact(statusUpdateInterval.toMillis()), TimeUnit.MILLISECONDS);
        }

        PGReplicationStream stream = streamBuilder.start();

        // TODO DBZ-508 get rid of this
        // Needed by tests when connections are opened and closed in a fast sequence
        try {
            Thread.sleep(10);
        } catch (Exception e) {
        }
        stream.forceUpdateStatus();
        return stream;
    }

    @Override
    public synchronized void close() {
        try {
            LOGGER.debug("Closing replication connection");
            super.close();
        }
        catch (Throwable e) {
            LOGGER.error("Unexpected error while closing Postgres connection", e);
        }
        if (dropSlotOnClose) {
            // we're dropping the replication slot via a regular - i.e. not a replication - connection
            try (PostgresConnection connection = new PostgresConnection(originalConfig)) {
                connection.dropReplicationSlot(slotName);
            }
            catch (Throwable e) {
                LOGGER.error("Unexpected error while dropping replication slot", e);
            }
        }
    }

    protected static void defaultSettings(Configuration.Builder builder) {
        // first copy the parent's default settings...
        PostgresConnection.defaultSettings(builder);
        // then set some additional replication specific settings
        builder.with("replication", "database")
               .with("preferQueryMode", "simple"); // replication protocol only supports simple query mode
    }

    protected static class ReplicationConnectionBuilder implements Builder {

        private final Configuration config;
        private String slotName = DEFAULT_SLOT_NAME;
        private PostgresConnectorConfig.LogicalDecoder plugin = PostgresConnectorConfig.LogicalDecoder.DECODERBUFS;
        private boolean dropSlotOnClose = DEFAULT_DROP_SLOT_ON_CLOSE;
        private Duration statusUpdateIntervalVal;
        private TypeRegistry typeRegistry;
        private Properties slotStreamParams = new Properties();

        protected ReplicationConnectionBuilder(Configuration config) {
            assert config != null;
            this.config = config;
        }

        @Override
        public ReplicationConnectionBuilder withSlot(final String slotName) {
            assert slotName != null;
            this.slotName = slotName;
            return this;
        }

        @Override
        public ReplicationConnectionBuilder withPlugin(final PostgresConnectorConfig.LogicalDecoder plugin) {
            assert plugin != null;
            this.plugin = plugin;
            return this;
        }

        @Override
        public ReplicationConnectionBuilder dropSlotOnClose(final boolean dropSlotOnClose) {
            this.dropSlotOnClose = dropSlotOnClose;
            return this;
        }

        @Override
        public ReplicationConnectionBuilder streamParams(final String slotStreamParams) {
            if(slotStreamParams != null && !slotStreamParams.isEmpty()) {
                this.slotStreamParams = new Properties();
                String[] paramsWithValues = slotStreamParams.split(";");
                for(String paramsWithValue : paramsWithValues) {
                    String[] paramAndValue = paramsWithValue.split("=");
                    if(paramAndValue.length == 2) {
                        this.slotStreamParams.setProperty(paramAndValue[0], paramAndValue[1]);
                    }
                    else {
                        LOGGER.warn("The following STREAM_PARAMS value is invalid: {}", paramsWithValue);
                    }
                }
            }
            return this;
        }

        @Override
        public ReplicationConnectionBuilder statusUpdateInterval(final Duration statusUpdateInterval) {
            this.statusUpdateIntervalVal = statusUpdateInterval;
            return this;
        }

        @Override
        public ReplicationConnection build() {
            assert plugin != null : "Decoding plugin name is not set";
            return new PostgresReplicationConnection(config, slotName, plugin, dropSlotOnClose, statusUpdateIntervalVal, typeRegistry, slotStreamParams);
        }

        @Override
        public Builder withTypeRegistry(TypeRegistry typeRegistry) {
            this.typeRegistry = typeRegistry;
            return this;
        }
    }
}
