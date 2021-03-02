package org.devocative.keights.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "keights.coredns")
public class CoreDNSProperties {
	private String configMapKey = "Corefile";
	private String configMap = "coredns";
	private String configMapNamespace = "kube-system";
	private Duration rewriteTaskDelay = Duration.ofSeconds(4);
	private RewriteConfig rewriteConfig = new RewriteConfig();

	// ------------------------------

	@Getter
	@Setter
	public static class RewriteConfig {
		private String annotation = "keights.coredns.rewrite/domain-name";
	}
}
