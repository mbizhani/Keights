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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.devocative.keights.config.CoreDNSProperties;
import org.devocative.keights.iservice.ICoreDNSService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import static org.devocative.keights.dto.EEventType.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class KeightsWatchService {
	private final CoreV1Api coreV1Api;
	private final CoreDNSProperties properties;
	private final ICoreDNSService coreDNSService;

	private V1ConfigMap coreDNSV1ConfigMap;

	// ------------------------------

	@PostConstruct
	public void init() {
		final var informerFactory = new SharedInformerFactory();

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
				final var coreDNSConfig = obj
					.getData()
					.get(properties.getConfigMapDataKey());
				coreDNSV1ConfigMap = obj;
				coreDNSService.handleCoreDNSConfigMap(Added, coreDNSConfig);
			}

			@Override
			public void onUpdate(V1ConfigMap oldObj, V1ConfigMap newObj) {
				final var coreDNSConfig = newObj
					.getData()
					.get(properties.getConfigMapDataKey());
				coreDNSV1ConfigMap = newObj;
				coreDNSService.handleCoreDNSConfigMap(Updated, coreDNSConfig);
			}

			@Override
			public void onDelete(V1ConfigMap obj, boolean deletedFinalStateUnknown) {
				log.error("CoreDNS ConfigMap REMOVED!!!");
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
				final var md = obj.getMetadata();
				coreDNSService.handleService(Added, md.getName(), md.getNamespace(), md.getAnnotations());
			}

			@Override
			public void onUpdate(V1Service oldObj, V1Service newObj) {
				final var md = newObj.getMetadata();
				coreDNSService.handleService(Updated, md.getName(), md.getNamespace(), md.getAnnotations());
			}

			@Override
			public void onDelete(V1Service obj, boolean deletedFinalStateUnknown) {
				final var md = obj.getMetadata();
				coreDNSService.handleService(Deleted, md.getName(), md.getNamespace(), md.getAnnotations());
			}
		});

		informerFactory.startAllRegisteredInformers();
	}

	@Scheduled(
		initialDelayString = "#{coreDNSProperties.rewriteTaskInitDelay}",
		fixedDelayString = "#{coreDNSProperties.rewriteTaskDelay}")
	public void processRequests() {
		final var coreDNSConfig = coreDNSV1ConfigMap.getData().get(properties.getConfigMapDataKey());
		final var optionalConfig = coreDNSService.processRequests(coreDNSConfig);
		optionalConfig.ifPresent(config -> {
			coreDNSV1ConfigMap.getData().put(properties.getConfigMapDataKey(), config);
			replaceCoreDNSV1ConfigMap();
		});
	}

	// ------------------------------

	private void replaceCoreDNSV1ConfigMap() {
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
}
