/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import java.sql.SQLException;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.ThreadSafe;
import io.debezium.connector.common.CdcSourceTaskContext;
import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.connector.postgresql.connection.ReplicationConnection;
import io.debezium.connector.postgresql.spi.SlotState;
import io.debezium.relational.TableId;
import io.debezium.schema.TopicSelector;
import io.debezium.util.Clock;
import io.debezium.util.ElapsedTimeStrategy;

/**
 * The context of a {@link PostgresConnectorTask}. This deals with most of the brunt of reading various configuration options
 * and creating other objects with these various options.
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
@ThreadSafe
public class PostgresTaskContext extends CdcSourceTaskContext {

    protected final static Logger LOGGER = LoggerFactory.getLogger(PostgresTaskContext.class);

    private final PostgresConnectorConfig config;
    private final TopicSelector<TableId> topicSelector;
    private final PostgresSchema schema;

    private ElapsedTimeStrategy refreshXmin;
    private Long lastXmin;

    protected PostgresTaskContext(PostgresConnectorConfig config, PostgresSchema schema, TopicSelector<TableId> topicSelector) {
        super(config.getContextName(), config.getLogicalName(), Collections::emptySet);

        this.config = config;
        if (config.xminFetchInterval().toMillis() > 0) {
            this.refreshXmin = ElapsedTimeStrategy.constant(Clock.SYSTEM, config.xminFetchInterval().toMillis());
        }
        this.topicSelector = topicSelector;
        assert schema != null;
        this.schema = schema;
    }

    protected TopicSelector<TableId> topicSelector() {
        return topicSelector;
    }

    protected PostgresSchema schema() {
        return schema;
    }

    protected PostgresConnectorConfig config() {
        return config;
    }

    protected void refreshSchema(boolean printReplicaIdentityInfo) throws SQLException {
        try (final PostgresConnection connection = createConnection()) {
            schema.refresh(connection, printReplicaIdentityInfo);
        }
    }

    Long getSlotXmin() throws SQLException {
        // when xmin fetch is set to 0, we don't track it to ignore any performance of querying the
        // slot periodically
        if (config.xminFetchInterval().toMillis() <= 0) {
            return null;
        }
        assert(this.refreshXmin != null);

        if (this.refreshXmin.hasElapsed()) {
            lastXmin = getCurrentSlotState().slotCatalogXmin();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Fetched new xmin from slot of {}", lastXmin);
            }
        }
        else {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("reusing xmin value of {}", lastXmin);
            }
        }

        return lastXmin;
    }

    private SlotState getCurrentSlotState() throws SQLException {
        try (final PostgresConnection connection = createConnection()) {
            return connection.getReplicationSlotState(config.slotName(), config.plugin().getPostgresPluginName());
        }
    }

    protected ReplicationConnection createReplicationConnection() throws SQLException {
        return ReplicationConnection.builder(config.jdbcConfig())
                                    .withSlot(config.slotName())
                                    .withPlugin(config.plugin())
                                    .dropSlotOnClose(config.dropSlotOnStop())
                                    .streamParams(config.streamParams())
                                    .statusUpdateInterval(config.statusUpdateInterval())
                                    .withTypeRegistry(schema.getTypeRegistry())
                                    .build();
    }

    protected PostgresConnection createConnection() {
        return new PostgresConnection(config.jdbcConfig());
    }

    PostgresConnectorConfig getConfig() {
        return config;
    }
}
