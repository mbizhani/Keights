package org.devocative.keights.service;

import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.devocative.keights.config.CoreDNSProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
@Service
public class CoreDNSService {
	private final CoreV1Api coreV1Api;
	private final ThreadPoolTaskExecutor taskExecutor;
	private final CoreDNSProperties properties;

	private final AtomicBoolean coreDNSConfigProcessed = new AtomicBoolean(false);
	private final Map<String, String> REWRITES = new ConcurrentHashMap<>();
	private final List<RewriteRequest> REQUESTS = Collections.synchronizedList(new ArrayList<>());

	private final Pattern clusterDomainNamePattern = Pattern.compile("kubernetes +([\\w.]+) ");
	private final Pattern rewritePattern = Pattern.compile("rewrite\\s+name\\s+(exact)?\\s*(?<SRC>(?![-.])[\\w-.]*\\w)\\s+(?<DST>(?![-.])[\\w-.]*\\w)");

	private SharedInformerFactory informerFactory;
	private String clusterDomainName;
	private V1ConfigMap coreDNSV1ConfigMap;

	// ------------------------------

	@PostConstruct
	public void init() {
		informerFactory = new SharedInformerFactory(coreV1Api.getApiClient(), taskExecutor.getThreadPoolExecutor());

		final SharedIndexInformer<V1ConfigMap> v1ConfigMapSII = informerFactory.sharedIndexInformerFor(params -> {
			log.debug("CoreDNSConfigMap, SharedIndexInformerFor.CallGeneratorParams: " +
					"resourceVersion=[{}], timeoutSeconds=[{}], watch=[{}]",
				params.resourceVersion, params.timeoutSeconds, params.watch);

			return coreV1Api.listNamespacedConfigMapCall(
				properties.getConfigMapNamespace(),
				null,
				null,
				null,
				"metadata.name=" + properties.getConfigMapName(),
				null,
				null,
				params.resourceVersion,
				null,
				params.timeoutSeconds,
				params.watch,
				null);
		}, V1ConfigMap.class, V1ConfigMapList.class);
		v1ConfigMapSII.addEventHandler(new ResourceEventHandler<>() {
			@Override
			public void onAdd(V1ConfigMap obj) {
				watchCoreDNSConfigMap("ADD", obj);
			}

			@Override
			public void onUpdate(V1ConfigMap oldObj, V1ConfigMap newObj) {
				watchCoreDNSConfigMap("UPDATE", newObj);
			}

			@Override
			public void onDelete(V1ConfigMap obj, boolean deletedFinalStateUnknown) {
			}
		});

		final SharedIndexInformer<V1Service> v1ServicesSII = informerFactory.sharedIndexInformerFor(params -> {
			log.debug("Services, SharedIndexInformerFor.CallGeneratorParams: " +
					"resourceVersion=[{}], timeoutSeconds=[{}], watch=[{}]",
				params.resourceVersion, params.timeoutSeconds, params.watch);

			return coreV1Api.listServiceForAllNamespacesCall(
				null,
				null,
				null,
				null,
				null,
				null,
				params.resourceVersion,
				null,
				params.timeoutSeconds,
				params.watch,
				null);

		}, V1Service.class, V1ServiceList.class);
		v1ServicesSII.addEventHandler(new ResourceEventHandler<>() {
			@Override
			public void onAdd(V1Service obj) {
				watchServices("ADD", obj);
			}

			@Override
			public void onUpdate(V1Service oldObj, V1Service newObj) {
				watchServices("UPDATE", newObj);
			}

			@Override
			public void onDelete(V1Service obj, boolean deletedFinalStateUnknown) {
				watchServices("DELETE", obj);
			}
		});

		informerFactory.startAllRegisteredInformers();
	}

	@Scheduled(
		initialDelayString = "#{coreDNSProperties.rewriteTaskInitDelay}",
		fixedDelayString = "#{coreDNSProperties.rewriteTaskDelay}")
	public void processRequests() {
		log.debug("Start Processing Rewrite Requests");

		if (!coreDNSConfigProcessed.get()) {
			log.warn("CoreDNS Not Inited!");
			return;
		}

		if (REQUESTS.size() > 0) {
			log.info("Rewrite Requests = [{}]", REQUESTS.size());

			final var local = new ArrayList<RewriteRequest>();
			synchronized (REQUESTS) {
				local.addAll(REQUESTS);
				REQUESTS.clear();
			}

			var modified = false;
			for (final var request : local) {
				final var serviceFqdn = request.toFQDN(clusterDomainName);

				if (!REWRITES.containsKey(request.getDomainName()) ||
					!REWRITES.get(request.getDomainName()).equals(serviceFqdn)) {
					modified = true;

					REWRITES
						.entrySet()
						.stream()
						.filter(entry -> entry.getValue().equals(serviceFqdn))
						.map(Map.Entry::getKey)
						.findFirst()
						.ifPresent(REWRITES::remove);

					REWRITES.put(request.getDomainName(), serviceFqdn);
				}
			}

			if (modified) {
				final var coreDNSV1ConfigMap = getCoreDNSV1ConfigMap();
				final var coreConfig = coreDNSV1ConfigMap
					.getData()
					.get(properties.getConfigMapDataKey());

				final var newConfigLines = Stream.of(coreConfig.split("\\n"))
					.filter(s -> !rewritePattern.matcher(s).find())
					.collect(Collectors.toList());

				REWRITES.forEach((key, value) -> newConfigLines.add(1,
					String.format("    rewrite name %s %s", key, value)));

				final var newConfig = String.join("\n", newConfigLines);
				log.info("CoreDNS New Config:\n{}", newConfig);

				coreDNSV1ConfigMap
					.getData()
					.put(properties.getConfigMapDataKey(), newConfig);

				replaceCoreDNSV1ConfigMap(coreDNSV1ConfigMap);
			}
		}
	}

	// ------------------------------

	private void watchCoreDNSConfigMap(String event, V1ConfigMap configMap) {
		coreDNSConfigProcessed.set(false);
		log.info("Watch CoreDNS CM: event=[{}]", event);

		coreDNSV1ConfigMap = configMap;
		final var coreDNSConfig = configMap
			.getData()
			.get(properties.getConfigMapDataKey());
		log.info("Watcher CoreDNS CM:\n{}", coreDNSConfig);

		final var matcher = clusterDomainNamePattern.matcher(coreDNSConfig);
		if (matcher.find()) {
			clusterDomainName = matcher.group(1);
			log.info("Cluster DomainName = [{}]", clusterDomainName);
		}

		final var rewrites = extractRewrites(coreDNSConfig);
		log.info("Watcher CoreDNS CM: rewrites={}", rewrites);

		REWRITES.clear();
		REWRITES.putAll(rewrites);
		coreDNSConfigProcessed.set(true);
	}

	private void watchServices(String event, V1Service v1Service) {
		log.info("Watch Service: type=[{}] name=[{}]", event, v1Service.getMetadata().getName());

		final var annotationKey = properties.getRewriteConfig().getAnnotation();
		final var annotations = v1Service.getMetadata().getAnnotations();
		if (annotations != null && annotations.containsKey(annotationKey)) {
			final var request = new RewriteRequest()
				.setType(event)
				.setDomainName(annotations.get(annotationKey))
				.setServiceName(v1Service.getMetadata().getName())
				.setServiceNamespace(v1Service.getMetadata().getNamespace());
			log.info("Watch Service: Request={}", request);
			REQUESTS.add(request);
		}
	}

	private Map<String, String> extractRewrites(String coreDnsConfig) {
		return Stream.of(coreDnsConfig.split("\\n"))
			.map(rewritePattern::matcher)
			.filter(Matcher::find)
			.map(m -> new String[]{m.group(2), m.group(3)})
			.collect(Collectors.toMap(arr -> arr[0], arr -> arr[1]));
	}

	private V1ConfigMap getCoreDNSV1ConfigMap() {
		return coreDNSV1ConfigMap;
		/*try {
			final var list = coreV1Api.listNamespacedConfigMap(
				properties.getConfigMapNamespace(),
				null,
				null,
				null,
				"metadata.name=" + properties.getConfigMapName(),
				null,
				null,
				null,
				null,
				null,
				null);
			final var items = list.getItems();
			return items.get(0);
		} catch (ApiException e) {
			log.error("getCoreDNSConfigMap", e);
			throw new RuntimeException(e);
		}*/
	}

	private void replaceCoreDNSV1ConfigMap(V1ConfigMap coreDNSV1ConfigMap) {
		try {
			coreV1Api.replaceNamespacedConfigMap(
				properties.getConfigMapName(),
				properties.getConfigMapNamespace(),
				coreDNSV1ConfigMap,
				null,
				null,
				null);
		} catch (ApiException e) {
			log.error("writeCoreDNSV1ConfigMap", e);
			throw new RuntimeException(e);
		}
	}

	// ------------------------------

	@Getter
	@Setter
	@Accessors(chain = true)
	@ToString
	private static class RewriteRequest {
		private String type;
		private String domainName;
		private String serviceName;
		private String serviceNamespace;

		public String toFQDN(String clusterDomain) {
			return String.format("%s.%s.svc.%s", serviceName, serviceNamespace, clusterDomain);
		}
	}

}
