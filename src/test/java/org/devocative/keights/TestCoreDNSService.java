package org.devocative.keights;

import org.devocative.keights.config.CoreDNSProperties;
import org.devocative.keights.dto.RewriteRequest;
import org.devocative.keights.iservice.ICoreDNSService;
import org.devocative.keights.service.CoreDNSService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.devocative.keights.dto.EEventType.Added;
import static org.junit.jupiter.api.Assertions.*;

public class TestCoreDNSService {
	private CoreDNSProperties properties;
	private ICoreDNSService coreDNSService;

	@BeforeEach
	public void init() {
		properties = new CoreDNSProperties();
		coreDNSService = new CoreDNSService(properties);
	}

	@Test
	public void test_init_add() {
		final var coreDNSConfig =
			".:53 {\n" +
				"    errors\n" +
				"    health {\n" +
				"        lameduck 5s\n" +
				"    }\n" +
				"    ready\n" +
				"    kubernetes cluster.local in-addr.arpa ip6.arpa {\n" +
				"        pods insecure\n" +
				"        fallthrough in-addr.arpa ip6.arpa\n" +
				"    }\n" +
				"    prometheus :9153\n" +
				"    forward . \"/etc/resolv.conf\"\n" +
				"    cache 30\n" +
				"    loop\n" +
				"    reload\n" +
				"    loadbalance\n" +
				"}";

		final var expectedCoreDNSConfig =
			".:53 {\n" +
				"    rewrite name mytest.site.wsx test.default.svc.cluster.local\n" +
				"    errors\n" +
				"    health {\n" +
				"        lameduck 5s\n" +
				"    }\n" +
				"    ready\n" +
				"    kubernetes cluster.local in-addr.arpa ip6.arpa {\n" +
				"        pods insecure\n" +
				"        fallthrough in-addr.arpa ip6.arpa\n" +
				"    }\n" +
				"    prometheus :9153\n" +
				"    forward . \"/etc/resolv.conf\"\n" +
				"    cache 30\n" +
				"    loop\n" +
				"    reload\n" +
				"    loadbalance\n" +
				"}";

		coreDNSService.handleCoreDNSConfigMap(Added, coreDNSConfig);
		assertEquals(0, coreDNSService.getRewritesSize());

		coreDNSService.handleService(
			Added,
			"test",
			"default",
			Map.of(properties.getRewriteConfig().getAnnotation(), "mytest.site.wsx"));

		final var newConfig = coreDNSService
			.processRequests(coreDNSConfig)
			.orElseThrow();
		assertEquals(expectedCoreDNSConfig, newConfig);
	}

	@Test
	public void test_config_with_rewrite() {
		final var coreDNSConfig =
			".:53 {\n" +
				"    rewrite name mytest.site.wsx test.default.svc.cluster.local\n" +
				"    errors\n" +
				"    health {\n" +
				"        lameduck 5s\n" +
				"    }\n" +
				"    ready\n" +
				"    kubernetes cluster.local in-addr.arpa ip6.arpa {\n" +
				"        pods insecure\n" +
				"        fallthrough in-addr.arpa ip6.arpa\n" +
				"    }\n" +
				"    prometheus :9153\n" +
				"    forward . \"/etc/resolv.conf\"\n" +
				"    cache 30\n" +
				"    loop\n" +
				"    reload\n" +
				"    loadbalance\n" +
				"}";

		coreDNSService.handleCoreDNSConfigMap(Added, coreDNSConfig);
		assertEquals(1, coreDNSService.getRewritesSize());

		assertTrue(coreDNSService.hasRewrite(
			new RewriteRequest("mytest.site.wsx", "test", "default")));

		assertFalse(coreDNSService.hasRewrite(
			new RewriteRequest("mytest.site.wsx", "foo", "default")));
	}

	@Test
	public void test_update() {
		final var coreDNSConfig =
			".:53 {\n" +
				"    rewrite name mytest.site.wsx test.default.svc.cluster.local\n" +
				"    errors\n" +
				"    health {\n" +
				"        lameduck 5s\n" +
				"    }\n" +
				"    ready\n" +
				"    kubernetes cluster.local in-addr.arpa ip6.arpa {\n" +
				"        pods insecure\n" +
				"        fallthrough in-addr.arpa ip6.arpa\n" +
				"    }\n" +
				"    prometheus :9153\n" +
				"    forward . \"/etc/resolv.conf\"\n" +
				"    cache 30\n" +
				"    loop\n" +
				"    reload\n" +
				"    loadbalance\n" +
				"}";

		final var expectedCoreDNSConfig =
			".:53 {\n" +
				"    rewrite name test.site.wsx test.default.svc.cluster.local\n" +
				"    errors\n" +
				"    health {\n" +
				"        lameduck 5s\n" +
				"    }\n" +
				"    ready\n" +
				"    kubernetes cluster.local in-addr.arpa ip6.arpa {\n" +
				"        pods insecure\n" +
				"        fallthrough in-addr.arpa ip6.arpa\n" +
				"    }\n" +
				"    prometheus :9153\n" +
				"    forward . \"/etc/resolv.conf\"\n" +
				"    cache 30\n" +
				"    loop\n" +
				"    reload\n" +
				"    loadbalance\n" +
				"}";

		coreDNSService.handleCoreDNSConfigMap(Added, coreDNSConfig);
		assertEquals(1, coreDNSService.getRewritesSize());

		coreDNSService.handleService(
			Added,
			"test",
			"default",
			Map.of(properties.getRewriteConfig().getAnnotation(), "test.site.wsx"));

		final var newConfig = coreDNSService
			.processRequests(coreDNSConfig)
			.orElseThrow();
		assertEquals(expectedCoreDNSConfig, newConfig);

		assertTrue(coreDNSService.hasRewrite(
			new RewriteRequest("test.site.wsx", "test", "default")));

	}
}
