/*
 * Copyright (c) 2016-2017 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.dcp.conductor;

import com.couchbase.client.core.config.CouchbaseBucketConfig;
import com.couchbase.client.core.config.NodeInfo;
import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.core.service.ServiceType;
import com.couchbase.client.core.state.LifecycleState;
import com.couchbase.client.core.state.NotConnectedException;
import com.couchbase.client.core.time.Delay;
import com.couchbase.client.dcp.buffer.BucketConfigHelper;
import com.couchbase.client.dcp.config.ClientEnvironment;
import com.couchbase.client.dcp.events.FailedToAddNodeEvent;
import com.couchbase.client.dcp.events.FailedToMovePartitionEvent;
import com.couchbase.client.dcp.events.FailedToRemoveNodeEvent;
import com.couchbase.client.dcp.state.PartitionState;
import com.couchbase.client.dcp.state.SessionState;
import com.couchbase.client.dcp.util.retry.RetryBuilder;
import com.couchbase.client.deps.io.netty.buffer.ByteBuf;
import com.couchbase.client.deps.io.netty.util.internal.ConcurrentSet;
import rx.Completable;
import rx.CompletableSubscriber;
import rx.Observable;
import rx.Single;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Action4;
import rx.functions.Func1;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.couchbase.client.core.logging.RedactableArgument.system;
import static com.couchbase.client.dcp.util.retry.RetryBuilder.anyOf;

public class Conductor {

    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(Conductor.class);

    private final ConfigProvider configProvider;
    private final Set<DcpChannel> channels = new ConcurrentSet<DcpChannel>();
    private volatile boolean stopped = true;
    private final ClientEnvironment env;
    private final AtomicReference<CouchbaseBucketConfig> currentConfig = new AtomicReference<CouchbaseBucketConfig>();
    private final boolean ownsConfigProvider;
    private final SessionState sessionState = new SessionState();

    public Conductor(final ClientEnvironment env, ConfigProvider cp) {
        this.env = env;
        configProvider = cp == null ? new HttpStreamingConfigProvider(env) : cp;
        ownsConfigProvider = cp == null;
        configProvider.configs().forEach(new Action1<CouchbaseBucketConfig>() {
            @Override
            public void call(CouchbaseBucketConfig config) {
                LOGGER.trace("Applying new configuration, new rev is {}.", config.rev());
                currentConfig.set(config);
                reconfigure(config);
            }
        });
    }

    public SessionState sessionState() {
        return sessionState;
    }

    public Completable connect() {
        stopped = false;
        Completable atLeastOneConfig = configProvider.configs().first().toCompletable()
                .timeout(env.bootstrapTimeout(), TimeUnit.SECONDS)
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        LOGGER.warn("Did not receive initial configuration from provider.");
                    }
                });
        return configProvider.start()
                .timeout(env.connectTimeout(), TimeUnit.SECONDS)
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        LOGGER.warn("Cannot connect configuration provider.");
                    }
                })
                .concatWith(atLeastOneConfig);
    }

    ConfigProvider configProvider() {
        return configProvider;
    }

    /**
     * Returns true if all channels and the config provider are in a disconnected state.
     */
    public boolean disconnected() {
        if (!configProvider.isState(LifecycleState.DISCONNECTED)) {
            return false;
        }

        for (DcpChannel channel : channels) {
            if (!channel.isState(LifecycleState.DISCONNECTED)) {
                return false;
            }
        }

        return true;
    }

    public Completable stop() {
        LOGGER.debug("Instructed to shutdown.");
        stopped = true;
        Completable channelShutdown = Observable
                .from(channels)
                .flatMapCompletable(new Func1<DcpChannel, Completable>() {
                    @Override
                    public Completable call(DcpChannel dcpChannel) {
                        return dcpChannel.disconnect();
                    }
                })
                .toCompletable();

        if (ownsConfigProvider) {
            channelShutdown = channelShutdown.andThen(configProvider.stop());
        }

        return channelShutdown.doOnCompleted(new Action0() {
            @Override
            public void call() {
                LOGGER.info("Shutdown complete.");
            }
        });
    }

    /**
     * Returns the total number of partitions.
     */
    public int numberOfPartitions() {
        CouchbaseBucketConfig config = currentConfig.get();
        return config.numberOfPartitions();
    }

    public Observable<ByteBuf> getSeqnos() {
        return Observable
                .from(channels)
                .flatMap(new Func1<DcpChannel, Observable<ByteBuf>>() {
                    @Override
                    public Observable<ByteBuf> call(DcpChannel channel) {
                        return getSeqnosForChannel(channel);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private Observable<ByteBuf> getSeqnosForChannel(final DcpChannel channel) {
        return Observable
                .just(channel)
                .flatMapSingle(new Func1<DcpChannel, Single<ByteBuf>>() {
                    @Override
                    public Single<ByteBuf> call(DcpChannel channel) {
                        return channel.getSeqnos();
                    }
                })
                .retryWhen(anyOf(NotConnectedException.class)
                        .max(Integer.MAX_VALUE)
                        .delay(Delay.fixed(200, TimeUnit.MILLISECONDS))
                        .doOnRetry(new Action4<Integer, Throwable, Long, TimeUnit>() {
                            @Override
                            public void call(Integer integer, Throwable throwable, Long aLong, TimeUnit timeUnit) {
                                LOGGER.debug("Rescheduling get Seqnos for channel {}, not connected (yet).", channel);

                            }
                        })
                        .build()
                );
    }

    @SuppressWarnings("unchecked")
    public Single<ByteBuf> getFailoverLog(final short partition) {
        return Observable
                .just(partition)
                .map(new Func1<Short, DcpChannel>() {
                    @Override
                    public DcpChannel call(Short aShort) {
                        return masterChannelByPartition(partition);
                    }
                })
                .flatMapSingle(new Func1<DcpChannel, Single<ByteBuf>>() {
                    @Override
                    public Single<ByteBuf> call(DcpChannel channel) {
                        return channel.getFailoverLog(partition);
                    }
                })
                .retryWhen(anyOf(NotConnectedException.class)
                        .max(Integer.MAX_VALUE)
                        .delay(Delay.fixed(200, TimeUnit.MILLISECONDS))
                        .doOnRetry(new Action4<Integer, Throwable, Long, TimeUnit>() {
                            @Override
                            public void call(Integer integer, Throwable throwable, Long aLong, TimeUnit timeUnit) {
                                LOGGER.debug("Rescheduling Get Failover Log for vbid {}, not connected (yet).", partition);

                            }
                        })
                        .build()
                ).toSingle();
    }

    @SuppressWarnings("unchecked")
    public Completable startStreamForPartition(final short partition, final long vbuuid, final long startSeqno,
                                               final long endSeqno, final long snapshotStartSeqno, final long snapshotEndSeqno) {
        return Observable
                .just(partition)
                .map(new Func1<Short, DcpChannel>() {
                    @Override
                    public DcpChannel call(Short aShort) {
                        return masterChannelByPartition(partition);
                    }
                })
                .flatMapCompletable(new Func1<DcpChannel, Completable>() {
                    @Override
                    public Completable call(DcpChannel channel) {
                        return channel.openStream(partition, vbuuid, startSeqno, endSeqno, snapshotStartSeqno, snapshotEndSeqno);
                    }
                })
                .retryWhen(anyOf(NotConnectedException.class)
                        .max(Integer.MAX_VALUE)
                        .delay(Delay.fixed(200, TimeUnit.MILLISECONDS))
                        .doOnRetry(new Action4<Integer, Throwable, Long, TimeUnit>() {
                            @Override
                            public void call(Integer integer, Throwable throwable, Long aLong, TimeUnit timeUnit) {
                                LOGGER.debug("Rescheduling Stream Start for vbid {}, not connected (yet).", partition);

                            }
                        })
                        .build()
                )
                .toCompletable();
    }

    public Completable stopStreamForPartition(final short partition) {
        if (streamIsOpen(partition)) {
            DcpChannel channel = masterChannelByPartition(partition);
            return channel.closeStream(partition);
        } else {
            return Completable.complete();
        }
    }

    public boolean streamIsOpen(final short partition) {
        DcpChannel channel = masterChannelByPartition(partition);
        return channel.streamIsOpen(partition);
    }

    /**
     * Returns the dcp channel responsible for a given vbucket id according to the current
     * configuration.
     * <p>
     * Note that this doesn't mean that the partition is enabled there, it just checks the current
     * mapping.
     */
    private DcpChannel masterChannelByPartition(short partition) {
        CouchbaseBucketConfig config = currentConfig.get();
        int index = config.nodeIndexForMaster(partition, false);
        NodeInfo node = config.nodeAtIndex(index);
        int port = (env.sslEnabled() ? node.sslServices() : node.services()).get(ServiceType.BINARY);
        InetSocketAddress address = new InetSocketAddress(node.hostname().nameOrAddress(), port);
        for (DcpChannel ch : channels) {
            if (ch.address().equals(address)) {
                return ch;
            }
        }

        throw new IllegalStateException("No DcpChannel found for partition " + partition);
    }

    private void reconfigure(CouchbaseBucketConfig config) {
        final List<InetSocketAddress> toAdd = new ArrayList<InetSocketAddress>();
        final List<DcpChannel> toRemove = new ArrayList<DcpChannel>();
        final BucketConfigHelper configHelper = new BucketConfigHelper(config, env.sslEnabled());
        final boolean onlyConnectToPrimaryPartition = !env.persistencePollingEnabled();

        for (NodeInfo node : configHelper.getDataNodes()) {
            if (onlyConnectToPrimaryPartition && !config.hasPrimaryPartitionsOnNode(node.hostname())) {
                continue;
            }

            final InetSocketAddress address = configHelper.getAddress(node);

            boolean found = false;
            for (DcpChannel chan : channels) {
                if (chan.address().equals(address)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                toAdd.add(address);
                LOGGER.debug("Planning to add {}", address);
            }
        }

        for (DcpChannel chan : channels) {
            boolean found = false;
            for (NodeInfo node : config.nodes()) {
                final InetSocketAddress address = configHelper.getAddress(node);
                if (address.equals(chan.address())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                LOGGER.debug("Planning to remove {}", chan);
                toRemove.add(chan);
            }
        }

        for (InetSocketAddress add : toAdd) {
            add(add);
        }

        for (DcpChannel remove : toRemove) {
            remove(remove);
        }
    }

    private void add(final InetSocketAddress node) {
        //noinspection SuspiciousMethodCalls: channel proxies equals/hashcode to its address
        if (channels.contains(node)) {
            return;
        }

        LOGGER.debug("Adding DCP Channel against {}", node);
        final DcpChannel channel = new DcpChannel(node, env, this);
        channels.add(channel);

        channel
                .connect()
                .retryWhen(RetryBuilder.anyMatches(new Func1<Throwable, Boolean>() {
                    @Override
                    public Boolean call(Throwable t) {
                        return !stopped;
                    }
                }).max(env.dcpChannelsReconnectMaxAttempts()).delay(env.dcpChannelsReconnectDelay()).
                        doOnRetry(new Action4<Integer, Throwable, Long, TimeUnit>() {
                            @Override
                            public void call(Integer integer, Throwable throwable, Long aLong, TimeUnit timeUnit) {
                                LOGGER.debug("Rescheduling Node reconnect for DCP channel {}", node);
                            }
                        }).build()).subscribe(new CompletableSubscriber() {
            @Override
            public void onCompleted() {
                LOGGER.debug("Completed Node connect for DCP channel {}", node);
            }

            @Override
            public void onError(Throwable e) {
                LOGGER.warn("Got error during connect (maybe retried) for node {}", system(node), e);
                if (env.eventBus() != null) {
                    env.eventBus().publish(new FailedToAddNodeEvent(node, e));
                }
            }

            @Override
            public void onSubscribe(Subscription d) {
                // ignored.
            }
        });
    }

    private void remove(final DcpChannel node) {
        if (channels.remove(node)) {
            LOGGER.debug("Removing DCP Channel against {}", node);

            for (short partition = 0; partition < node.streamIsOpen.length(); partition++) {
                if (node.streamIsOpen(partition)) {
                    maybeMovePartition(partition);
                }
            }

            node.disconnect().subscribe(new CompletableSubscriber() {
                @Override
                public void onCompleted() {
                    LOGGER.debug("Channel remove notified as complete for {}", node.address());
                }

                @Override
                public void onError(Throwable e) {
                    LOGGER.warn("Got error during Node removal for node {}", system(node.address()), e);
                    if (env.eventBus() != null) {
                        env.eventBus().publish(new FailedToRemoveNodeEvent(node.address(), e));
                    }
                }

                @Override
                public void onSubscribe(Subscription d) {
                    // ignored.
                }
            });
        }
    }

    /**
     * Called by the {@link DcpChannel} to signal a stream end done by the server and it
     * most likely needs to be moved over to a new node during rebalance/failover.
     *
     * @param partition the partition to move if needed
     */
    @SuppressWarnings("unchecked")
    void maybeMovePartition(final short partition) {
        Observable
                .timer(50, TimeUnit.MILLISECONDS)
                .filter(new Func1<Long, Boolean>() {
                    @Override
                    public Boolean call(Long aLong) {
                        PartitionState ps = sessionState.get(partition);
                        boolean desiredSeqnoReached = ps.isAtEnd();
                        if (desiredSeqnoReached) {
                            LOGGER.debug("Reached desired high seqno {} for vbucket {}, not reopening stream.",
                                    ps.getEndSeqno(), partition);
                        }
                        return !desiredSeqnoReached;
                    }
                })
                .flatMapCompletable(new Func1<Long, Completable>() {
                    @Override
                    public Completable call(Long aLong) {
                        PartitionState ps = sessionState.get(partition);
                        return startStreamForPartition(
                                partition,
                                ps.getLastUuid(),
                                ps.getStartSeqno(),
                                ps.getEndSeqno(),
                                ps.getSnapshotStartSeqno(),
                                ps.getSnapshotEndSeqno()
                        ).retryWhen(anyOf(NotMyVbucketException.class)
                                .max(Integer.MAX_VALUE)
                                .delay(Delay.fixed(200, TimeUnit.MILLISECONDS))
                                .build());
                    }
                }).toCompletable().subscribe(new CompletableSubscriber() {
            @Override
            public void onCompleted() {
                LOGGER.trace("Completed Partition Move for partition {}", partition);
            }

            @Override
            public void onError(Throwable e) {
                LOGGER.warn("Error during Partition Move for partition " + partition, e);
                if (env.eventBus() != null) {
                    env.eventBus().publish(new FailedToMovePartitionEvent(partition, e));
                }
            }

            @Override
            public void onSubscribe(Subscription d) {
                LOGGER.debug("Subscribing for Partition Move for partition {}", partition);
            }
        });
    }

}
