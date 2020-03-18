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
package com.couchbase.client.dcp.transport.netty;

import com.couchbase.client.dcp.core.logging.RedactableArgument;
import com.couchbase.client.dcp.buffer.DcpBucketConfig;
import com.couchbase.client.dcp.config.ClientEnvironment;
import com.couchbase.client.dcp.config.HostAndPort;
import com.couchbase.client.dcp.core.config.AlternateAddress;
import com.couchbase.client.dcp.core.config.BucketConfig;
import com.couchbase.client.dcp.core.config.CouchbaseBucketConfig;
import com.couchbase.client.dcp.core.config.NodeInfo;
import com.couchbase.client.dcp.core.config.parser.BucketConfigParser;
import com.couchbase.client.dcp.core.env.NetworkResolution;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.subjects.Subject;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * This handler is responsible to consume chunks of JSON configs via HTTP, aggregate them and once a complete
 * config is received send it into a {@link Subject} for external usage.
 */
class ConfigHandler extends SimpleChannelInboundHandler<HttpObject> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigHandler.class);

  /**
   * The config stream where the configs are emitted into.
   */
  private final Subject<DcpBucketConfig, DcpBucketConfig> configStream;

  /**
   * The revision of the last config emitted. Only emit a config
   * if it is newer than this revision.
   */
  private final AtomicLong currentBucketConfigRev;

  private final ClientEnvironment environment;

  /**
   * The current aggregated chunk of the JSON config.
   */
  private ByteBuf responseContent;

  private boolean hasDeterminedAlternateNetworkName = false;

  private String alternateNetworkName; // null means use primary

  /**
   * Creates a new config handler.
   *
   * @param configStream config stream where to send the configs.
   * @param currentBucketConfigRev revision of last received config.
   * @param environment the environment.
   */
  ConfigHandler(
      final Subject<DcpBucketConfig, DcpBucketConfig> configStream,
      final AtomicLong currentBucketConfigRev,
      final ClientEnvironment environment) {
    this.configStream = requireNonNull(configStream);
    this.currentBucketConfigRev = requireNonNull(currentBucketConfigRev);
    this.environment = requireNonNull(environment);
  }

  /**
   * If we get a new content chunk, send it towards decoding.
   */
  @Override
  protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) throws Exception {
    if (msg instanceof HttpContent) {
      HttpContent content = (HttpContent) msg;
      decodeChunk((InetSocketAddress) ctx.channel().remoteAddress(), content.content());
    }
  }

  /**
   * Helper method to decode and analyze the chunk.
   *
   * @param chunk the chunk to analyze.
   */
  private void decodeChunk(InetSocketAddress address, final ByteBuf chunk) {
    responseContent.writeBytes(chunk);

    final String currentChunk = responseContent.toString(UTF_8);
    final int separatorIndex = currentChunk.indexOf("\n\n\n\n");

    if (separatorIndex <= 0) {
      return;
    }

    Metrics.timer("dcp.parse.config").record(() -> {
      String rawConfig = currentChunk
          .substring(0, separatorIndex)
          .trim()
          .replace("$HOST", address.getAddress().getHostAddress());

      String origin = address.getAddress().getHostAddress();
      CouchbaseBucketConfig config = (CouchbaseBucketConfig) BucketConfigParser.parse(rawConfig, environment, origin);
      synchronized (currentBucketConfigRev) {
        if (config.rev() > currentBucketConfigRev.get()) {
          selectAlternateNetwork(config);
          LOGGER.trace("Publishing bucket config: {}", RedactableArgument.system(rawConfig));
          currentBucketConfigRev.set(config.rev());
          configStream.onNext(new DcpBucketConfig(config, environment.sslEnabled()));
        } else {
          LOGGER.trace("Ignoring config, since rev has not changed.");
        }
      }

      responseContent.clear();
      responseContent.writeBytes(currentChunk.substring(separatorIndex + 4).getBytes(UTF_8));
    });
  }

  private void selectAlternateNetwork(CouchbaseBucketConfig config) {
    if (!hasDeterminedAlternateNetworkName) {
      final Set<String> seedHosts = environment.clusterAt().stream()
          .map(HostAndPort::host)
          .collect(Collectors.toSet());
      alternateNetworkName = determineNetworkResolution(config, environment.networkResolution(), seedHosts);
      hasDeterminedAlternateNetworkName = true;

      String displayName = alternateNetworkName == null ? "<default>" : alternateNetworkName;
      if (NetworkResolution.AUTO.equals(environment.networkResolution())) {
        displayName = "auto -> " + displayName;
      }
      LOGGER.info("Selected network: {}", displayName);
    }
    config.useAlternateNetwork(alternateNetworkName);
  }

  /**
   * Helper method to figure out which network resolution should be used.
   *
   * if DEFAULT is selected, then null is returned which is equal to the "internal" or default
   * config mode. If AUTO is used then we perform the select heuristic based off of the seed
   * hosts given. All other resolution settings (i.e. EXTERNAL) are returned directly and are
   * considered to be part of the alternate address configs.
   *
   * @param config the config to check against
   * @param nr the network resolution setting from the environment
   * @param seedHosts the seed hosts from bootstrap for autoconfig.
   * @return the found setting if external is used, null if internal/default is used.
   */
  public static String determineNetworkResolution(final BucketConfig config, final NetworkResolution nr,
                                                  final Set<String> seedHosts) {
    if (nr.equals(NetworkResolution.DEFAULT)) {
      return null;
    } else if (nr.equals(NetworkResolution.AUTO)) {
      for (NodeInfo info : config.nodes()) {
        if (seedHosts.contains(info.hostname())) {
          return null;
        }

        Map<String, AlternateAddress> aa = info.alternateAddresses();
        if (aa != null && !aa.isEmpty()) {
          for (Map.Entry<String, AlternateAddress> entry : aa.entrySet()) {
            AlternateAddress alternateAddress = entry.getValue();
            if (alternateAddress != null && seedHosts.contains(alternateAddress.hostname())) {
              return entry.getKey();
            }
          }
        }
      }
      return null;
    } else {
      return nr.name();
    }
  }

  /**
   * Once the handler is added, initialize the response content buffer.
   */
  @Override
  public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
    responseContent = ctx.alloc().buffer();
  }

  /**
   * Once the handler is removed, make sure the response content is released and freed.
   */
  @Override
  public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
    if (responseContent != null && responseContent.refCnt() > 0) {
      responseContent.release();
      responseContent = null;
    }
  }
}
