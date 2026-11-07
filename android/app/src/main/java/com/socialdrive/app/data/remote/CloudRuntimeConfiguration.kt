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
    val cognito: CognitoRuntimeConfiguration,
) {
    companion object {
        fun parse(
            apiBaseUrl: String,
            uploadHosts: String,
            consentPolicyVersion: String,
            cognitoRegion: String,
            cognitoUserPoolId: String,
            cognitoClientId: String,
        ): CloudRuntimeConfiguration? {
            if (apiBaseUrl.isBlank() || uploadHosts.isBlank() || consentPolicyVersion.isBlank()) return null
            if (!consentPolicyVersion.matches(Regex("[A-Za-z0-9._-]{1,32}"))) return null
            val cognito = CognitoRuntimeConfiguration.parse(cognitoRegion, cognitoUserPoolId, cognitoClientId)
                ?: return null
            val hosts = uploadHosts.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            if (hosts.isEmpty() || hosts.any { it != it.lowercase() || !it.isValidCloudHost() }) return null
            return runCatching {
                val apiUri = URI(apiBaseUrl)
                if (apiUri.host?.lowercase()?.isValidCloudHost() != true) return null
                CloudRuntimeConfiguration(
                    client = CloudDriveClientConfig(apiUri, hosts),
                    consentPolicyVersion = consentPolicyVersion,
                    cognito = cognito,
                )
            }.getOrNull()
        }
    }
}

internal fun String.isValidCloudHost(): Boolean {
    if (length !in 4..253 || startsWith('.') || endsWith('.')) return false
    val labels = split('.')
    if (labels.size < 2 || labels.last().none(Char::isLetter)) return false
    return labels.all { label ->
        label.length in 1..63 &&
            label.first().isLetterOrDigit() &&
            label.last().isLetterOrDigit() &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}

/** Public Cognito mobile-app identifiers only. This value must never contain a client secret. */
data class CognitoRuntimeConfiguration(
    val region: String,
    val userPoolId: String,
    val clientId: String,
) {
    companion object {
        private val regionPattern = Regex("[a-z]{2}(?:-gov)?-[a-z]+-\\d")
        private val userPoolPattern = Regex("([a-z]{2}(?:-gov)?-[a-z]+-\\d)_[A-Za-z0-9]{9}")
        private val clientIdPattern = Regex("[a-z0-9]{26}")

        fun parse(region: String, userPoolId: String, clientId: String): CognitoRuntimeConfiguration? {
            val normalizedRegion = region.trim().lowercase()
            val normalizedPool = userPoolId.trim()
            val normalizedClient = clientId.trim().lowercase()
            if (!regionPattern.matches(normalizedRegion)) return null
            val poolRegion = userPoolPattern.matchEntire(normalizedPool)?.groupValues?.get(1) ?: return null
            if (poolRegion != normalizedRegion || !clientIdPattern.matches(normalizedClient)) return null
            return CognitoRuntimeConfiguration(normalizedRegion, normalizedPool, normalizedClient)
        }
    }
}
