/*
 * Copyright 2017-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.util.HashSet;
import java.util.Set;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.ImmutableSet;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.central.v09support.AggregateDaoWithV09Support;
import org.glowroot.central.v09support.GaugeValueDaoWithV09Support;
import org.glowroot.central.v09support.SyntheticResultDaoWithV09Support;
import org.glowroot.central.v09support.TraceDaoWithV09Support;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.util.RollupLevelService;

import static com.google.common.base.Preconditions.checkNotNull;

public class CentralRepoModule {

    private final AgentConfigDao agentConfigDao;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final ConfigRepositoryImpl configRepository;
    private final RollupLevelService rollupLevelService;
    private final ActiveAgentDao activeAgentDao;
    private final EnvironmentDao environmentDao;
    private final HeartbeatDao heartbeatDao;
    private final IncidentDao incidentDao;
    private final TransactionTypeDao transactionTypeDao;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final SyntheticResultDao syntheticResultDao;
    private final V09AgentRollupDao v09AgentRollupDao;

    public CentralRepoModule(ClusterManager clusterManager, Session session,
            String cassandraSymmetricEncryptionKey, Clock clock) throws Exception {
        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        agentConfigDao = new AgentConfigDao(session, clusterManager);
        userDao = new UserDao(session, clusterManager);
        roleDao = new RoleDao(session, clusterManager);
        configRepository = new ConfigRepositoryImpl(centralConfigDao, agentConfigDao, userDao,
                roleDao, cassandraSymmetricEncryptionKey);
        rollupLevelService = new RollupLevelService(configRepository, clock);
        activeAgentDao = new ActiveAgentDao(session, agentConfigDao, configRepository,
                rollupLevelService, clock);
        environmentDao = new EnvironmentDao(session);
        heartbeatDao = new HeartbeatDao(session, clock);
        incidentDao = new IncidentDao(session, clock);
        transactionTypeDao = new TransactionTypeDao(session, configRepository, clusterManager);
        traceAttributeNameDao =
                new TraceAttributeNameDao(session, configRepository, clusterManager);

        Set<String> agentRollupIdsWithV09Data;
        long v09LastCaptureTime;
        long v09FqtLastExpirationTime;
        long v09TraceLastExpirationTime;
        long v09AggregateLastExpirationTime;
        if (session.getTable("v09_agent_check") == null) {
            agentRollupIdsWithV09Data = ImmutableSet.of();
            v09LastCaptureTime = 0;
            v09FqtLastExpirationTime = 0;
            v09TraceLastExpirationTime = 0;
            v09AggregateLastExpirationTime = 0;
        } else {
            agentRollupIdsWithV09Data = new HashSet<>();
            ResultSet results =
                    session.execute("select agent_id from v09_agent_check where one = 1");
            for (Row row : results) {
                String agentId = checkNotNull(row.getString(0));
                for (String agentRollupId : AgentRollupIds.getAgentRollupIds(agentId)) {
                    agentRollupIdsWithV09Data.add(agentRollupId);
                }
            }
            results = session.execute("select v09_last_capture_time, v09_fqt_last_expiration_time,"
                    + " v09_trace_last_expiration_time, v09_aggregate_last_expiration_time from"
                    + " v09_last_capture_time where one = 1");
            Row row = checkNotNull(results.one());
            int i = 0;
            v09LastCaptureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            v09FqtLastExpirationTime = checkNotNull(row.getTimestamp(i++)).getTime();
            v09TraceLastExpirationTime = checkNotNull(row.getTimestamp(i++)).getTime();
            v09AggregateLastExpirationTime = checkNotNull(row.getTimestamp(i++)).getTime();
        }
        FullQueryTextDao fullQueryTextDao = new FullQueryTextDao(session, configRepository);
        AggregateDaoImpl aggregateDaoImpl = new AggregateDaoImpl(session, activeAgentDao,
                transactionTypeDao, fullQueryTextDao, configRepository, clock);
        GaugeValueDaoImpl gaugeValueDaoImpl =
                new GaugeValueDaoImpl(session, configRepository, clock);
        SyntheticResultDaoImpl syntheticResultDaoImpl =
                new SyntheticResultDaoImpl(session, configRepository, clock);
        if (v09AggregateLastExpirationTime < clock.currentTimeMillis()) {
            aggregateDao = aggregateDaoImpl;
            gaugeValueDao = gaugeValueDaoImpl;
            syntheticResultDao = syntheticResultDaoImpl;
        } else {
            aggregateDao = new AggregateDaoWithV09Support(agentRollupIdsWithV09Data,
                    v09LastCaptureTime, v09FqtLastExpirationTime, clock, aggregateDaoImpl);
            gaugeValueDao = new GaugeValueDaoWithV09Support(agentRollupIdsWithV09Data,
                    v09LastCaptureTime, clock, gaugeValueDaoImpl);
            syntheticResultDao = new SyntheticResultDaoWithV09Support(agentRollupIdsWithV09Data,
                    v09LastCaptureTime, clock, syntheticResultDaoImpl);
        }
        TraceDaoImpl traceDaoImpl = new TraceDaoImpl(session, transactionTypeDao, fullQueryTextDao,
                traceAttributeNameDao, configRepository, clock);
        if (v09TraceLastExpirationTime < clock.currentTimeMillis()) {
            traceDao = traceDaoImpl;
        } else {
            traceDao = new TraceDaoWithV09Support(agentRollupIdsWithV09Data, v09LastCaptureTime,
                    v09FqtLastExpirationTime, clock, traceDaoImpl);
        }
        // need to create V09AgentRollupDao as long as new v09 agents may connect in the future
        v09AgentRollupDao = new V09AgentRollupDao(session, clusterManager);
    }

    public AgentConfigDao getAgentConfigDao() {
        return agentConfigDao;
    }

    public UserDao getUserDao() {
        return userDao;
    }

    public RoleDao getRoleDao() {
        return roleDao;
    }

    public ConfigRepositoryImpl getConfigRepository() {
        return configRepository;
    }

    public RollupLevelService getRollupLevelService() {
        return rollupLevelService;
    }

    public ActiveAgentDao getActiveAgentDao() {
        return activeAgentDao;
    }

    public EnvironmentDao getEnvironmentDao() {
        return environmentDao;
    }

    public HeartbeatDao getHeartbeatDao() {
        return heartbeatDao;
    }

    public IncidentDao getIncidentDao() {
        return incidentDao;
    }

    public TransactionTypeDao getTransactionTypeDao() {
        return transactionTypeDao;
    }

    public TraceAttributeNameDao getTraceAttributeNameDao() {
        return traceAttributeNameDao;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public TraceDao getTraceDao() {
        return traceDao;
    }

    public GaugeValueDao getGaugeValueDao() {
        return gaugeValueDao;
    }

    public SyntheticResultDao getSyntheticResultDao() {
        return syntheticResultDao;
    }

    public V09AgentRollupDao getV09AgentRollupDao() {
        return v09AgentRollupDao;
    }
}
