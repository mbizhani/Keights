package org.devocative.keights.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "keights.coredns")
class CoreDNSProperties {
    var configMapKey = "Corefile"
    var configMap = "coredns"
    var configMapNamespace = "kube-system"
    var rewriteTaskDelay: Duration = Duration.ofSeconds(4)
    var rewriteConfig = RewriteConfig()

    // ------------------------------

    class RewriteConfig {
        var annotation = "keights.coredns.rewrite/domain-name"
    }
}
