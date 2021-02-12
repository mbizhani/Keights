package org.devocative.keights.iservice;

import org.devocative.keights.dto.EEventType;
import org.devocative.keights.dto.RewriteRequest;

import java.util.Map;
import java.util.Optional;

public interface ICoreDNSService {
	void handleCoreDNSConfigMap(EEventType event, String coreDNSConfig);

	boolean handleService(EEventType event, String name, String namespace, Map<String, String> annotations);

	Optional<String> processRequests(String coreDNSConfig);

	boolean hasRewrite(RewriteRequest request);

	int getRewritesSize();
}
