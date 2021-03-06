/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.PickFirstBalancerFactory;
import io.grpc.Status;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;

final class AutoConfiguredLoadBalancerFactory extends LoadBalancer.Factory {
  private static final Logger logger =
      Logger.getLogger(AutoConfiguredLoadBalancerFactory.class.getName());

  @VisibleForTesting
  static final String ROUND_ROUND_LOAD_BALANCER_FACTORY_NAME =
      "io.grpc.util.RoundRobinLoadBalancerFactory";
  @VisibleForTesting
  static final String GRPCLB_LOAD_BALANCER_FACTORY_NAME =
      "io.grpc.grpclb.GrpclbLoadBalancerFactory";

  AutoConfiguredLoadBalancerFactory() {}

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new AutoConfiguredLoadBalancer(helper);
  }

  private static final class EmptySubchannelPicker extends SubchannelPicker {

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
    }
  }

  @VisibleForTesting
  static final class AutoConfiguredLoadBalancer extends LoadBalancer {
    private final Helper helper;
    private LoadBalancer delegate;
    private LoadBalancer.Factory delegateFactory;

    AutoConfiguredLoadBalancer(Helper helper) {
      this.helper = helper;
      setDelegateFactory(PickFirstBalancerFactory.getInstance());
      setDelegate(getDelegateFactory().newLoadBalancer(helper));
    }

    //  Must be run inside ChannelExecutor.
    @Override
    public void handleResolvedAddressGroups(
        List<EquivalentAddressGroup> servers, Attributes attributes) {
      if (attributes.keys().contains(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG)) {
        Factory newlbf = decideLoadBalancerFactory(
            servers, attributes.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG));
        if (newlbf != null && newlbf != delegateFactory) {
          helper.updateBalancingState(ConnectivityState.CONNECTING, new EmptySubchannelPicker());
          getDelegate().shutdown();
          setDelegateFactory(newlbf);
          setDelegate(getDelegateFactory().newLoadBalancer(helper));
        }
      }
      getDelegate().handleResolvedAddressGroups(servers, attributes);
    }

    @Override
    public void handleNameResolutionError(Status error) {
      getDelegate().handleNameResolutionError(error);
    }

    @Override
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      getDelegate().handleSubchannelState(subchannel, stateInfo);
    }

    @Override
    public void shutdown() {
      getDelegate().shutdown();
      setDelegate(null);
    }

    @VisibleForTesting
    LoadBalancer getDelegate() {
      return delegate;
    }

    @VisibleForTesting
    void setDelegate(LoadBalancer delegate) {
      this.delegate = delegate;
    }

    @VisibleForTesting
    LoadBalancer.Factory getDelegateFactory() {
      return delegateFactory;
    }

    @VisibleForTesting
    void setDelegateFactory(LoadBalancer.Factory delegateFactory) {
      this.delegateFactory = delegateFactory;
    }

    /**
     * Picks a load balancer based on given criteria.  In order of preference:
     *
     * <ol>
     *   <li>User provided lb on the channel.  This is a degenerate case and not handled here.</li>
     *   <li>gRPCLB if on the class path and any gRPC LB balancer addresses are present</li>
     *   <li>RoundRobin if on the class path and picked by the service config</li>
     *   <li>PickFirst if the service config choice does not specify</li>
     * </ol>
     *
     * @param servers The list of servers reported
     * @param config the service config object
     * @return the new load balancer factory, or null if the existing lb should be used.
     */
    @Nullable
    @VisibleForTesting
    static LoadBalancer.Factory decideLoadBalancerFactory(
        List<EquivalentAddressGroup> servers, Map<String, Object> config) {

      // Check for balancer addresses
      boolean haveBalancerAddress = false;
      for (EquivalentAddressGroup s : servers) {
        if (s.getAttributes().get(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY) != null) {
          haveBalancerAddress = true;
          break;
        }
      }

      if (haveBalancerAddress) {
        try {
          Class<?> lbFactoryClass = Class.forName(GRPCLB_LOAD_BALANCER_FACTORY_NAME);
          Method getInstance = lbFactoryClass.getMethod("getInstance");
          return (LoadBalancer.Factory) getInstance.invoke(null);
        } catch (RuntimeException e) {
          throw e;
        } catch (Exception e) {
          throw new RuntimeException("Can't get GRPCLB, but balancer addresses were present", e);
        }
      }

      String serviceConfigChoiceBalancingPolicy =
          ServiceConfigUtil.getLoadBalancingPolicy(config);

      // Check for an explicitly present lb choice
      if (serviceConfigChoiceBalancingPolicy != null) {
        if (serviceConfigChoiceBalancingPolicy.toUpperCase(Locale.ROOT).equals("ROUND_ROBIN")) {
          try {
            Class<?> lbFactoryClass = Class.forName(ROUND_ROUND_LOAD_BALANCER_FACTORY_NAME);
            Method getInstance = lbFactoryClass.getMethod("getInstance");
            return (LoadBalancer.Factory) getInstance.invoke(null);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new RuntimeException("Can't get Round Robin LB", e);
          }
        }
        throw new IllegalArgumentException(
            "Unknown service config policy: " + serviceConfigChoiceBalancingPolicy);
      }

      return PickFirstBalancerFactory.getInstance();
    }
  }
}
