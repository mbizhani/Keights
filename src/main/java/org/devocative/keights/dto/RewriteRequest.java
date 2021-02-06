package org.devocative.keights.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
@ToString
public class RewriteRequest {
	private EEventType event;
	private String domainName;
	private String serviceName;
	private String serviceNamespace;

	public RewriteRequest(String domainName, String serviceName, String serviceNamespace) {
		this.domainName = domainName;
		this.serviceName = serviceName;
		this.serviceNamespace = serviceNamespace;
	}

	public String toFQDN(String clusterDomain) {
		return String.format("%s.%s.svc.%s", serviceName, serviceNamespace, clusterDomain);
	}
}
