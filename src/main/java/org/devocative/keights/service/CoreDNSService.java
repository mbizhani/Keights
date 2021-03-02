package org.devocative.keights.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.devocative.keights.KeightsException;
import org.devocative.keights.config.CoreDNSProperties;
import org.devocative.keights.dto.EEventType;
import org.devocative.keights.dto.RewriteRequest;
import org.devocative.keights.iservice.ICoreDNSService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
@Service
public class CoreDNSService implements ICoreDNSService {
	private final CoreDNSProperties properties;

	private final AtomicBoolean coreDNSConfigProcessed = new AtomicBoolean(false);
	private final Map<String, String> REWRITES = new ConcurrentHashMap<>();
	private final List<RewriteRequest> REQUESTS = Collections.synchronizedList(new ArrayList<>());

	private final Pattern clusterDomainNamePattern = Pattern.compile("kubernetes +([\\w.]+) ");
	private final Pattern rewritePattern = Pattern.compile("rewrite\\s+name\\s+(exact)?\\s*(?<SRC>(?![-.])[\\w-.]*\\w)\\s+(?<DST>(?![-.])[\\w-.]*\\w)");

	private String clusterDomainName;

	// ------------------------------

	@Override
	public void handleCoreDNSConfigMap(EEventType event, String coreDNSConfig) {
		coreDNSConfigProcessed.set(false);
		log.info("Handle CoreDNS: event=[{}]\n{}", event, coreDNSConfig);

		final var matcher = clusterDomainNamePattern.matcher(coreDNSConfig);
		if (matcher.find()) {
			clusterDomainName = matcher.group(1);
			log.info("Cluster DomainName = [{}]", clusterDomainName);
		}

		final var rewrites = extractRewrites(coreDNSConfig);
		log.info("Handle CoreDNS: Rewrites={}", rewrites);

		REWRITES.clear();
		REWRITES.putAll(rewrites);
		coreDNSConfigProcessed.set(true);
	}

	@Override
	public boolean handleService(EEventType event, String name, String namespace, Map<String, String> annotations) {
		log.info("Handle Service: event=[{}] name=[{}]", event, name);

		boolean rqAdded = false;
		final var annotationKey = properties.getRewriteConfig().getAnnotation();
		if (annotations != null && annotations.containsKey(annotationKey)) {
			final var request = new RewriteRequest()
				.setEvent(event)
				.setDomainName(annotations.get(annotationKey))
				.setServiceName(name)
				.setServiceNamespace(namespace);
			log.info("Handle Service: Add Rq={}", request);
			REQUESTS.add(request);
			rqAdded = true;
		}

		return rqAdded;
	}

	@Override
	public Optional<String> processRequests(String coreDNSConfig) {
		log.info("Start Processing Rewrite Requests");

		if (!coreDNSConfigProcessed.get()) {
			log.warn("CoreDNS Not Inited!");
			return Optional.empty();
		}

		if (REQUESTS.isEmpty()) {
			return Optional.empty();
		}

		log.info("Rewrite Requests Size = [{}]", REQUESTS.size());

		final var local = new ArrayList<RewriteRequest>();
		synchronized (REQUESTS) {
			local.addAll(REQUESTS);
			REQUESTS.clear();
		}

		var modified = false;
		for (final var request : local) {
			final var serviceFqdn = request.toFQDN(clusterDomainName);

			switch (request.getEvent()) {
				case Added:
				case Updated:
					if (!hasRewrite(request)) {
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
					break;

				case Deleted:
					//TODO
					break;
				default:
					throw new KeightsException("Unsupported Event: %s", request.getEvent());
			}
		}

		if (modified) {
			final var newConfigLines = Stream.of(coreDNSConfig.split("\\n"))
				.filter(s -> !rewritePattern.matcher(s).find())
				.collect(Collectors.toList());

			REWRITES.forEach((key, value) -> newConfigLines.add(1,
				String.format("    rewrite name %s %s", key, value)));

			final var newConfig = String.join("\n", newConfigLines);
			log.info("CoreDNS New Config:\n{}", newConfig);

			return Optional.of(newConfig);
		}

		return Optional.empty();
	}

	@Override
	public boolean hasRewrite(RewriteRequest request) {
		final var rqSvcFqdn = request.toFQDN(clusterDomainName);

		final var svcFQDN = REWRITES.get(request.getDomainName());

		return svcFQDN != null && svcFQDN.equals(rqSvcFqdn);
	}

	@Override
	public int getRewritesSize() {
		return REWRITES.size();
	}

	// ------------------------------

	private Map<String, String> extractRewrites(String coreDnsConfig) {
		return Stream.of(coreDnsConfig.split("\\n"))
			.map(rewritePattern::matcher)
			.filter(Matcher::find)
			.map(m -> new String[]{m.group(2), m.group(3)})
			.collect(Collectors.toMap(arr -> arr[0], arr -> arr[1]));
	}
}
