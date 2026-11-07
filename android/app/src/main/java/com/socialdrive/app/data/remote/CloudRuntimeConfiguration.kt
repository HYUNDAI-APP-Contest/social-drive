package com.socialdrive.app.data.remote

import java.net.URI
import com.socialdrive.app.domain.CapabilityStatus
import kotlinx.coroutines.flow.StateFlow

interface CloudConnectionStatusSource {
    val status: StateFlow<CapabilityStatus>
}

data class CloudRuntimeConfiguration(
    val client: CloudDriveClientConfig,
    val consentPolicyVersion: String,
) {
    companion object {
        fun parse(apiBaseUrl: String, uploadHosts: String, consentPolicyVersion: String): CloudRuntimeConfiguration? {
            if (apiBaseUrl.isBlank() || uploadHosts.isBlank() || consentPolicyVersion.isBlank()) return null
            if (!consentPolicyVersion.matches(Regex("[A-Za-z0-9._-]{1,32}"))) return null
            val hosts = uploadHosts.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::lowercase)
                .toSet()
            if (hosts.isEmpty() || hosts.any { !it.matches(Regex("[a-z0-9.-]+")) }) return null
            return runCatching {
                CloudRuntimeConfiguration(
                    client = CloudDriveClientConfig(URI(apiBaseUrl), hosts),
                    consentPolicyVersion = consentPolicyVersion,
                )
            }.getOrNull()
        }
    }
}
