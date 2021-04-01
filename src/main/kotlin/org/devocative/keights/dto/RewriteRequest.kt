package org.devocative.keights.dto

data class RewriteRequest(
    var event: EEventType,
    val domainName: String,
    val serviceName: String,
    val serviceNamespace: String
) {
    //var event: EEventType? = null

    fun toFQDN(clusterDomain: String) = "$serviceName.$serviceNamespace.svc.$clusterDomain"
}
