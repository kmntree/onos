/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.onos.store.cluster.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static org.onlab.util.Tools.namedThreads;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ClusterService;
import org.onlab.onos.cluster.ControllerNode;
import org.onlab.onos.cluster.Leadership;
import org.onlab.onos.cluster.LeadershipEvent;
import org.onlab.onos.cluster.LeadershipEventListener;
import org.onlab.onos.cluster.LeadershipService;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.event.AbstractListenerRegistry;
import org.onlab.onos.event.EventDeliveryService;
import org.onlab.onos.store.hz.StoreService;
import org.onlab.onos.store.serializers.KryoNamespaces;
import org.onlab.onos.store.serializers.KryoSerializer;
import org.onlab.util.KryoNamespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.hazelcast.config.TopicConfig;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;

/**
 * Distributed implementation of LeadershipService that is based on Hazelcast.
 * <p>
 * The election is eventually-consistent: if there is Hazelcast partitioning,
 * and the partitioning is healed, there could be a short window of time
 * until the leaders in each partition discover each other. If this happens,
 * the leaders release the leadership and run again for election.
 * </p>
 * <p>
 * The leader election is based on Hazelcast's Global Lock, which is stongly
 * consistent. In addition, each leader periodically advertises events
 * (using a Hazelcast Topic) that it is the elected leader. Those events are
 * used for two purposes: (1) Discover multi-leader collisions (in case of
 * healed Hazelcast partitions), and (2) Inform all listeners who is
 * the current leader (e.g., for informational purpose).
 * </p>
 */
@Component(immediate = true)
@Service
public class HazelcastLeadershipService implements LeadershipService {
    private static final Logger log =
        LoggerFactory.getLogger(HazelcastLeadershipService.class);

    private static final KryoSerializer SERIALIZER = new KryoSerializer() {
        @Override
        protected void setupKryoPool() {
            serializerPool = KryoNamespace.newBuilder()
                .register(KryoNamespaces.API)
                .build()
                .populate(1);
        }
    };

    private static final long LEADERSHIP_PERIODIC_INTERVAL_MS = 5 * 1000; // 5s
    private static final long LEADERSHIP_REMOTE_TIMEOUT_MS = 15 * 1000;  // 15s

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StoreService storeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected EventDeliveryService eventDispatcher;

    private AbstractListenerRegistry<LeadershipEvent, LeadershipEventListener>
        listenerRegistry;
    private final Map<String, Topic> topics = Maps.newConcurrentMap();
    private ControllerNode localNode;

    @Activate
    protected void activate() {
        localNode = clusterService.getLocalNode();
        listenerRegistry = new AbstractListenerRegistry<>();
        eventDispatcher.addSink(LeadershipEvent.class, listenerRegistry);

        log.info("Hazelcast Leadership Service started");
    }

    @Deactivate
    protected void deactivate() {
        eventDispatcher.removeSink(LeadershipEvent.class);

        for (Topic topic : topics.values()) {
            topic.stop();
        }
        topics.clear();

        log.info("Hazelcast Leadership Service stopped");
    }

    @Override
    public ControllerNode getLeader(String path) {
        Topic topic = topics.get(path);
        if (topic == null) {
            return null;
        }
        return topic.leader();
    }

    @Override
    public void runForLeadership(String path) {
        checkArgument(path != null);
        Topic topic = new Topic(path);
        Topic oldTopic = topics.putIfAbsent(path, topic);
        if (oldTopic == null) {
            topic.start();
        }
    }

    @Override
    public void withdraw(String path) {
        checkArgument(path != null);
        Topic topic = topics.get(path);
        if (topic != null) {
            topic.stop();
            topics.remove(path, topic);
        }
    }

    @Override
    public Map<String, Leadership> getLeaderBoard() {
        throw new UnsupportedOperationException("I don't know what to do." +
                                                        " I wish you luck.");
    }

    @Override
    public void addListener(LeadershipEventListener listener) {
        listenerRegistry.addListener(listener);
    }

    @Override
    public void removeListener(LeadershipEventListener listener) {
        listenerRegistry.removeListener(listener);
    }

    /**
     * Class for keeping per-topic information.
     */
    private final class Topic implements MessageListener<byte[]> {
        private final String topicName;
        private volatile boolean isShutdown = true;
        private volatile long lastLeadershipUpdateMs = 0;
        private ExecutorService leaderElectionExecutor;

        private ControllerNode leader;
        private Lock leaderLock;
        private Future<?> getLockFuture;
        private Future<?> periodicProcessingFuture;
        private ITopic<byte[]> leaderTopic;
        private String leaderTopicRegistrationId;

        /**
         * Constructor.
         *
         * @param topicName the topic name
         */
        private Topic(String topicName) {
            this.topicName = topicName;
        }

        /**
         * Gets the leader for the topic.
         *
         * @return the leader for the topic
         */
        private ControllerNode leader() {
            return leader;
        }

        /**
         * Starts leadership election for the topic.
         */
        private void start() {
            isShutdown = false;
            String lockHzId = "LeadershipService/" + topicName + "/lock";
            String topicHzId = "LeadershipService/" + topicName + "/topic";
            leaderLock = storeService.getHazelcastInstance().getLock(lockHzId);

            String threadPoolName =
                "sdnip-leader-election-" + topicName + "-%d";
            leaderElectionExecutor = Executors.newScheduledThreadPool(2,
                                        namedThreads(threadPoolName));

            TopicConfig topicConfig = new TopicConfig();
            topicConfig.setGlobalOrderingEnabled(true);
            topicConfig.setName(topicHzId);
            storeService.getHazelcastInstance().getConfig().addTopicConfig(topicConfig);

            leaderTopic =
                storeService.getHazelcastInstance().getTopic(topicHzId);
            leaderTopicRegistrationId = leaderTopic.addMessageListener(this);

            getLockFuture = leaderElectionExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        doLeaderElectionThread();
                    }
                });
            periodicProcessingFuture =
                leaderElectionExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        doPeriodicProcessing();
                    }
                });
        }

        /**
         * Stops leadership election for the topic.
         */
        private void stop() {
            isShutdown = true;
            leaderTopic.removeMessageListener(leaderTopicRegistrationId);
            // getLockFuture.cancel(true);
            // periodicProcessingFuture.cancel(true);
            leaderElectionExecutor.shutdownNow();
        }

        @Override
        public void onMessage(Message<byte[]> message) {
            LeadershipEvent leadershipEvent =
                SERIALIZER.decode(message.getMessageObject());
            NodeId eventLeaderId = leadershipEvent.subject().leader().id();

            log.debug("SDN-IP Leadership Event: time = {} type = {} event = {}",
                      leadershipEvent.time(), leadershipEvent.type(),
                      leadershipEvent);
            if (!leadershipEvent.subject().topic().equals(topicName)) {
                return;         // Not our topic: ignore
            }
            if (eventLeaderId.equals(localNode.id())) {
                return;         // My own message: ignore
            }

            synchronized (this) {
                switch (leadershipEvent.type()) {
                case LEADER_ELECTED:
                    // FALLTHROUGH
                case LEADER_REELECTED:
                    //
                    // Another leader: if we are also a leader, then give up
                    // leadership and run for re-election.
                    //
                    if ((leader != null) &&
                        leader.id().equals(localNode.id())) {
                        getLockFuture.cancel(true);
                    } else {
                        // Just update the current leader
                        leader = leadershipEvent.subject().leader();
                        lastLeadershipUpdateMs = System.currentTimeMillis();
                    }
                    eventDispatcher.post(leadershipEvent);
                    break;
                case LEADER_BOOTED:
                    // Remove the state for the current leader
                    if ((leader != null) &&
                        eventLeaderId.equals(leader.id())) {
                        leader = null;
                    }
                    eventDispatcher.post(leadershipEvent);
                    break;
                default:
                    break;
                }
            }
        }

        private void doPeriodicProcessing() {

            while (!isShutdown) {

                //
                // Periodic tasks:
                // (a) Advertise ourselves as the leader
                //   OR
                // (b) Expire a stale (remote) leader
                //
                synchronized (this) {
                    LeadershipEvent leadershipEvent;
                    if (leader != null) {
                        if (leader.id().equals(localNode.id())) {
                            //
                            // Advertise ourselves as the leader
                            //
                            leadershipEvent = new LeadershipEvent(
                                LeadershipEvent.Type.LEADER_REELECTED,
                                new Leadership(topicName, localNode, 0));
                            // Dispatch to all remote instances
                            leaderTopic.publish(SERIALIZER.encode(leadershipEvent));
                        } else {
                            //
                            // Test if time to expire a stale leader
                            //
                            long delta = System.currentTimeMillis() -
                                lastLeadershipUpdateMs;
                            if (delta > LEADERSHIP_REMOTE_TIMEOUT_MS) {
                                leadershipEvent = new LeadershipEvent(
                                        LeadershipEvent.Type.LEADER_BOOTED,
                                        new Leadership(topicName, leader, 0));
                                // Dispatch only to the local listener(s)
                                eventDispatcher.post(leadershipEvent);
                                leader = null;
                            }
                        }
                    }
                }

                // Sleep before re-advertising
                try {
                    Thread.sleep(LEADERSHIP_PERIODIC_INTERVAL_MS);
                } catch (InterruptedException e) {
                    log.debug("SDN-IP Leader Election periodic thread interrupted");
                }
            }
        }

        /**
         * Performs the leader election by using Hazelcast.
         */
        private void doLeaderElectionThread() {

            while (!isShutdown) {
                LeadershipEvent leadershipEvent;
                //
                // Try to acquire the lock and keep it until the instance is
                // shutdown.
                //
                log.debug("SDN-IP Leader Election begin for topic {}",
                          topicName);
                try {
                    // Block until it becomes the leader
                    leaderLock.lockInterruptibly();
                } catch (InterruptedException e) {
                    //
                    // Thread interrupted. Either shutdown or run for
                    // re-election.
                    //
                    log.debug("SDN-IP Election interrupted for topic {}",
                              topicName);
                    continue;
                }

                synchronized (this) {
                    //
                    // This instance is now the leader
                    //
                    log.info("SDN-IP Leader Elected for topic {}", topicName);
                    leader = localNode;
                    leadershipEvent = new LeadershipEvent(
                        LeadershipEvent.Type.LEADER_ELECTED,
                        new Leadership(topicName, localNode, 0));
                    eventDispatcher.post(leadershipEvent);
                    leaderTopic.publish(SERIALIZER.encode(leadershipEvent));
                }

                try {
                    // Sleep forever until interrupted
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    //
                    // Thread interrupted. Either shutdown or run for
                    // re-election.
                    //
                    log.debug("SDN-IP Leader Interrupted for topic {}",
                              topicName);
                }

                synchronized (this) {
                    // If we reach here, we should release the leadership
                    log.debug("SDN-IP Leader Lock Released for topic {}",
                              topicName);
                    if ((leader != null) &&
                        leader.id().equals(localNode.id())) {
                        leader = null;
                    }
                    leadershipEvent = new LeadershipEvent(
                                LeadershipEvent.Type.LEADER_BOOTED,
                                new Leadership(topicName, localNode, 0));
                    eventDispatcher.post(leadershipEvent);
                    leaderTopic.publish(SERIALIZER.encode(leadershipEvent));
                    leaderLock.unlock();
                }
            }
        }
    }
}