/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.ribbon;

import java.net.URI;

import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.ClassUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.netflix.appinfo.InstanceInfo.PortType;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ConfigurationBasedServerList;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IPing;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.LoadBalancerBuilder;
import com.netflix.loadbalancer.NoOpPing;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerList;
import com.netflix.loadbalancer.ServerListFilter;
import com.netflix.loadbalancer.ZoneAvoidanceRule;
import com.netflix.loadbalancer.ZoneAwareLoadBalancer;
import com.netflix.niws.client.http.RestClient;
import com.netflix.niws.loadbalancer.DiscoveryEnabledServer;
import com.netflix.servo.monitor.Monitors;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.client.apache4.ApacheHttpClient4;

/**
 * @author Dave Syer
 */
@SuppressWarnings("deprecation")
@Configuration
@EnableConfigurationProperties
public class RibbonClientConfiguration {

	@Value("${ribbon.client.name}")
	private String name = "client";

	// TODO: maybe re-instate autowired load balancers: identified by name they could be
	// associated with ribbon clients

	@Bean
	@ConditionalOnMissingBean
	public IClientConfig ribbonClientConfig() {
		DefaultClientConfigImpl config = new DefaultClientConfigImpl();
		config.loadProperties(this.name);
		return config;
	}

	@Bean
	@ConditionalOnMissingBean
	public IRule ribbonRule(IClientConfig config) {
		ZoneAvoidanceRule rule = new ZoneAvoidanceRule();
		rule.initWithNiwsConfig(config);
		return rule;
	}

	@Bean
	@ConditionalOnMissingBean
	public IPing ribbonPing(IClientConfig config) {
		// TODO: use PingUrl
		return new NoOpPing();
	}

	@Bean
	@ConditionalOnMissingBean
	public ServerList<Server> ribbonServerList(IClientConfig config) {
		ConfigurationBasedServerList serverList = new ConfigurationBasedServerList();
		serverList.initWithNiwsConfig(config);
		return serverList;
	}

	/**
	 * Create a Netflix {@link RestClient} integrated with Ribbon if none already exists in the
	 * application context. It is not required for Ribbon to work properly and is therefore
	 * created lazily if ever another component requires it.
	 *
	 * @param config the configuration to use by the underlying Ribbon instance
	 * @param loadBalancer the load balancer to use by the underlying Ribbon instance
	 * @return a {@link RestClient} instances backed by Ribbon
	 */
	@Bean
	@Lazy
	@ConditionalOnMissingBean
	public RestClient ribbonRestClient(IClientConfig config, ILoadBalancer loadBalancer) {
		RestClient client = new OverrideRestClient(config);
		client.setLoadBalancer(loadBalancer);
		Monitors.registerObject("Client_" + this.name, client);
		return client;
	}

	@Bean
	@ConditionalOnMissingBean
	public ILoadBalancer ribbonLoadBalancer(IClientConfig config,
			ServerList<Server> serverList, ServerListFilter<Server> serverListFilter,
			IRule rule, IPing ping) {
		ZoneAwareLoadBalancer<Server> balancer = LoadBalancerBuilder.newBuilder()
				.withClientConfig(config).withRule(rule).withPing(ping)
				.withServerListFilter(serverListFilter).withDynamicServerList(serverList)
				.buildDynamicServerListLoadBalancer();
		return balancer;
	}

	@Bean
	@ConditionalOnMissingBean
	public ServerListFilter<Server> ribbonServerListFilter(IClientConfig config) {
		ZonePreferenceServerListFilter filter = new ZonePreferenceServerListFilter();
		filter.initWithNiwsConfig(config);
		return filter;
	}

	@Bean
	@ConditionalOnMissingBean
	public RibbonLoadBalancerContext ribbonLoadBalancerContext(
			ILoadBalancer loadBalancer, IClientConfig config) {
		return new RibbonLoadBalancerContext(loadBalancer, config);
	}

	static class OverrideRestClient extends RestClient {

		protected OverrideRestClient(IClientConfig ncc) {
			super();
			initWithNiwsConfig(ncc);
		}

		@Override
		public URI reconstructURIWithServer(Server server, URI original) {
			String scheme = original.getScheme();
			if (!"https".equals(scheme) && isSecure(server)) {
				original = UriComponentsBuilder.fromUri(original).scheme("https").build().toUri();
			}
			return super.reconstructURIWithServer(server, original);
		}

		private boolean isSecure(Server server) {
			if (ClassUtils.isPresent("com.netflix.niws.loadbalancer.DiscoveryEnabledServer",
					null)) {
				if (server instanceof DiscoveryEnabledServer) {
					DiscoveryEnabledServer enabled = (DiscoveryEnabledServer) server;
					return enabled.getInstanceInfo().isPortEnabled(PortType.SECURE);
				}
			}
			// Can we do better?
			return (""+server.getPort()).endsWith("443");
		}

		@Override
		protected Client apacheHttpClientSpecificInitialization() {
			ApacheHttpClient4 apache = (ApacheHttpClient4) super
					.apacheHttpClientSpecificInitialization();
			apache.getClientHandler()
					.getHttpClient()
					.getParams()
					.setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.IGNORE_COOKIES);
			return apache;
		}

	}

}
