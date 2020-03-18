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

import com.couchbase.client.dcp.core.config.NodeInfo;
import com.couchbase.client.dcp.core.service.ServiceType;
import com.couchbase.client.dcp.core.state.AbstractStateMachine;
import com.couchbase.client.dcp.core.state.LifecycleState;
import com.couchbase.client.dcp.buffer.DcpBucketConfig;
import com.couchbase.client.dcp.config.ClientEnvironment;
import com.couchbase.client.dcp.config.HostAndPort;
import com.couchbase.client.dcp.metrics.MetricsContext;
import com.couchbase.client.dcp.transport.netty.ChannelUtils;
import com.couchbase.client.dcp.transport.netty.ConfigPipeline;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.CompletableSubscriber;
import rx.Observable;
import rx.Subscriber;
import rx.subjects.BehaviorSubject;
import rx.subjects.Subject;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.couchbase.client.dcp.core.logging.RedactableArgument.system;
import static com.couchbase.client.dcp.util.retry.RetryBuilder.any;

/**
 * The {@link HttpStreamingConfigProvider}s only purpose is to keep new configs coming in all the time in a resilient manner.
 *
 * @author Michael Nitschinger
 */
public class HttpStreamingConfigProvider extends AbstractStateMachine<LifecycleState> implements ConfigProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpStreamingConfigProvider.class);

  private final AtomicReference<List<HostAndPort>> remoteHosts;
  private final Subject<DcpBucketConfig, DcpBucketConfig> configStream;
  private final AtomicLong currentBucketConfigRev = new AtomicLong(-1);
  private volatile boolean stopped = false;
  private volatile Channel channel;
  private final ClientEnvironment env;
  private final MetricsContext metrics = new MetricsContext("dcp.config");

  public HttpStreamingConfigProvider(final ClientEnvironment env) {
    super(LifecycleState.DISCONNECTED);
    this.env = env;
    this.remoteHosts = new AtomicReference<>(env.clusterAt());
    this.configStream = BehaviorSubject.<DcpBucketConfig>create().toSerialized();

    configStream.subscribe(new Subscriber<DcpBucketConfig>() {
      @Override
      public void onCompleted() {
        LOGGER.debug("Config stream completed.");
      }

      @Override
      public void onError(Throwable e) {
        LOGGER.warn("Error on config stream!", e);
      }

      @Override
      public void onNext(DcpBucketConfig config) {
        List<HostAndPort> newNodes = new ArrayList<>();
        for (NodeInfo node : config.nodes()) {
          Integer port = (env.sslEnabled() ? node.sslServices() : node.services()).get(ServiceType.CONFIG);
          newNodes.add(new HostAndPort(node.hostname(), port));
        }

        LOGGER.trace("Updated config stream node list to {}.", newNodes);
        remoteHosts.set(newNodes);
      }
    });
  }

  @Override
  public Completable start() {
    return tryConnectHosts();
  }

  @Override
  public Completable stop() {
    stopped = true;

    return Completable.create(new Completable.OnSubscribe() {
      @Override
      public void call(final CompletableSubscriber subscriber) {
        LOGGER.debug("Initiating streaming config provider shutdown on channel.");
        transitionState(LifecycleState.DISCONNECTING);
        if (channel != null) {
          Channel ch = channel;
          channel = null;
          ch.close().addListener(new GenericFutureListener<ChannelFuture>() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
              transitionState(LifecycleState.DISCONNECTED);
              if (future.isSuccess()) {
                LOGGER.debug("Streaming config provider channel shutdown completed.");
                subscriber.onCompleted();
              } else {
                LOGGER.warn("Error during streaming config provider shutdown!", future.cause());
                subscriber.onError(future.cause());
              }
            }
          });
        } else {
          subscriber.onCompleted();
        }
      }
    });
  }

  @Override
  public Observable<DcpBucketConfig> configs() {
    return configStream;
  }

  private Completable tryConnectHosts() {
    if (stopped) {
      LOGGER.debug("Not trying to connect to hosts, already stopped.");
      return Completable.complete();
    }

    transitionState(LifecycleState.CONNECTING);
    List<HostAndPort> hosts = remoteHosts.get();
    Completable chain = tryConnectHost(hosts.get(0));
    for (int i = 1; i < hosts.size(); i++) {
      final HostAndPort h = hosts.get(i);
      chain = chain.onErrorResumeNext(throwable -> {
        LOGGER.warn("Could not get config from Node, trying next in list.", throwable);
        return tryConnectHost(h);
      });
    }
    return chain;
  }

  private Completable tryConnectHost(final HostAndPort hostAndPort) {
    ByteBufAllocator allocator = env.poolBuffers()
        ? PooledByteBufAllocator.DEFAULT : UnpooledByteBufAllocator.DEFAULT;
    final InetSocketAddress address = hostAndPort.toAddress();
    final Bootstrap bootstrap = new Bootstrap()
        .remoteAddress(address)
        .option(ChannelOption.ALLOCATOR, allocator)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) env.socketConnectTimeout())
        .channel(ChannelUtils.channelForEventLoopGroup(env.eventLoopGroup()))
        .handler(new ConfigPipeline(env, address, configStream, currentBucketConfigRev))
        .group(env.eventLoopGroup());

    final String remote = hostAndPort.host() + ":" + hostAndPort.port();

    return Completable.create(new Completable.OnSubscribe() {
      @Override
      public void call(final CompletableSubscriber subscriber) {
        bootstrap.connect().addListener(new GenericFutureListener<ChannelFuture>() {
          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            metrics.newActionCounter("connect")
                .tag("remote", remote)
                .build()
                .track(future);

            if (future.isSuccess()) {
              channel = future.channel();
              channel.closeFuture().addListener(new GenericFutureListener<ChannelFuture>() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                  metrics.newEventCounter("channel.closed")
                      .tag("remote", remote)
                      .build()
                      .increment();

                  transitionState(LifecycleState.DISCONNECTED);
                  channel = null;
                  triggerReconnect();
                }
              });
              LOGGER.debug("Successfully established config connection to Socket {}", channel.remoteAddress());
              transitionState(LifecycleState.CONNECTED);
              subscriber.onCompleted();
            } else {
              subscriber.onError(future.cause());
            }
          }
        });
      }
    });
  }

  private void triggerReconnect() {
    transitionState(LifecycleState.CONNECTING);
    if (!stopped) {
      tryConnectHosts()
          .retryWhen(any()
              .delay(env.configProviderReconnectDelay())
              .max(env.configProviderReconnectMaxAttempts())
              .doOnRetry((retry, cause, delay, delayUnit) ->
                  LOGGER.info("No host usable to fetch a config from, waiting and retrying (remote hosts: {}).",
                      system(remoteHosts.get())))
              .build()
          )
          .subscribe();
    }
  }

}
