package org.devocative.keights.service;

import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.devocative.keights.KeightsException;
import org.devocative.keights.config.CoreDNSProperties;
import org.devocative.keights.iservice.ICoreDNSService;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.devocative.keights.dto.EEventType.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class KeightsWatchService {
	private final CoreV1Api coreV1Api;
	private final CoreDNSProperties properties;
	private final ICoreDNSService coreDNSService;
	private final TaskScheduler taskScheduler;

	private final AtomicBoolean hasTask = new AtomicBoolean(false);
	private V1ConfigMap coreDNSV1ConfigMap;

	// ------------------------------

	@PostConstruct
	public void init() {
		final var informerFactory = new SharedInformerFactory();

		final var v1ConfigMapSII = informerFactory.sharedIndexInformerFor(params -> {
			log.debug("CoreDNSConfigMap, SharedIndexInformerFor.CallGeneratorParams: " +
					"resourceVersion=[{}], timeoutSeconds=[{}], watch=[{}]",
				params.resourceVersion, params.timeoutSeconds, params.watch);

			return coreV1Api.listNamespacedConfigMapCall(
				properties.getConfigMapNamespace(),
				null,
				null,
				null,
				"metadata.name=" + properties.getConfigMap(),
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
					.get(properties.getConfigMapKey());
				coreDNSV1ConfigMap = obj;
				coreDNSService.handleCoreDNSConfigMap(Added, coreDNSConfig);
			}

			@Override
			public void onUpdate(V1ConfigMap oldObj, V1ConfigMap newObj) {
				final var coreDNSConfig = newObj
					.getData()
					.get(properties.getConfigMapKey());
				coreDNSV1ConfigMap = newObj;
				coreDNSService.handleCoreDNSConfigMap(Updated, coreDNSConfig);
			}

			@Override
			public void onDelete(V1ConfigMap obj, boolean deletedFinalStateUnknown) {
				log.error("CoreDNS ConfigMap REMOVED!!!");
			}
		});

		final var v1ServicesSII = informerFactory.sharedIndexInformerFor(params -> {
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
				final var rqAdded = coreDNSService.handleService(Added, md.getName(), md.getNamespace(), md.getAnnotations());
				if (rqAdded) {
					resetTask();
				}
			}

			@Override
			public void onUpdate(V1Service oldObj, V1Service newObj) {
				final var md = newObj.getMetadata();
				final var rqAdded = coreDNSService.handleService(Updated, md.getName(), md.getNamespace(), md.getAnnotations());
				if (rqAdded) {
					resetTask();
				}
			}

			@Override
			public void onDelete(V1Service obj, boolean deletedFinalStateUnknown) {
				final var md = obj.getMetadata();
				final var rqAdded = coreDNSService.handleService(Deleted, md.getName(), md.getNamespace(), md.getAnnotations());
				if (rqAdded) {
					resetTask();
				}
			}
		});

		informerFactory.startAllRegisteredInformers();
	}

	// ------------------------------

	private V1ConfigMap getCoreDNSV1ConfigMap() {
		if (coreDNSV1ConfigMap == null) {
			throw new KeightsException("CoreDNS ConfigMap Not Found: name=[%s] namespace=[%s]",
				properties.getConfigMap(), properties.getConfigMapNamespace());
		}
		return coreDNSV1ConfigMap;
	}

	private void processRequests() {
		hasTask.set(false);

		final var coreDNSConfig = getCoreDNSV1ConfigMap().getData().get(properties.getConfigMapKey());
		final var optionalConfig = coreDNSService.processRequests(coreDNSConfig);
		optionalConfig.ifPresent(config -> {
			getCoreDNSV1ConfigMap().getData().put(properties.getConfigMapKey(), config);
			replaceCoreDNSV1ConfigMap();
		});
	}

	private void replaceCoreDNSV1ConfigMap() {
		try {
			coreV1Api.replaceNamespacedConfigMap(
				properties.getConfigMap(),
				properties.getConfigMapNamespace(),
				getCoreDNSV1ConfigMap(),
				null,
				null,
				null);
		} catch (ApiException e) {
			log.error("writeCoreDNSV1ConfigMap", e);
			throw new KeightsException(e);
		}
	}

	private synchronized void resetTask() {
		if (!hasTask.get()) {
			taskScheduler.schedule(this::processRequests, Instant.now().plus(properties.getRewriteTaskDelay()));
			hasTask.set(true);
		}
	}
}
