package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.UUID

/**
 * Result of validating a user-entered server address.
 *
 * Exactly one of [normalized] / [error] is set. [normalized] is the safe,
 * canonical base URL (scheme + host + optional port + optional deployment
 * path) with dashboard path segments and trailing slashes removed.
 */
data class ServerUrlValidation(
    val normalized: String?,
    val error: String?
) {
    val isValid: Boolean get() = normalized != null
}

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("device_manager_prefs", Context.MODE_PRIVATE)

    private val _isPairedFlow = MutableStateFlow(isPaired())
    val isPairedFlow: StateFlow<Boolean> = _isPairedFlow.asStateFlow()

    private val _lastSyncFlow = MutableStateFlow(getLastSyncTime())
    val lastSyncFlow: StateFlow<Long> = _lastSyncFlow.asStateFlow()

    /**
     * Configured server base URL, or "" when the user has not configured a
     * server yet. There is intentionally NO built-in default deployment URL:
     * device data must never be sent to a host the user did not choose.
     */
    fun getServerUrl(): String {
        val saved = prefs.getString(KEY_SERVER_URL, null)
        if (saved.isNullOrBlank()) return ""
        return validateServerUrl(saved).normalized ?: ""
    }

    fun isServerConfigured(): Boolean = getServerUrl().isNotBlank()

    /**
     * Web dashboard address. The backend serves the dashboard from the same
     * origin as the API (SPA at /), so the configured server URL IS the
     * dashboard address. Returns "" until a server is configured.
     */
    fun getPublicWebUrl(): String = getServerUrl()

    /**
     * Validates and persists a server URL. Blank input clears the stored URL
     * (back to the "not configured" state). Invalid input is rejected with an
     * error message and never overwrites the previously saved URL.
     *
     * @return null on success, or a human-readable error message.
     */
    fun setServerUrl(url: String): String? {
        if (url.isBlank()) {
            prefs.edit().remove(KEY_SERVER_URL).apply()
            return null
        }
        val validation = validateServerUrl(url)
        val normalized = validation.normalized ?: return validation.error
        prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
        return null
    }

    fun getUserEmail(): String? {
        return prefs.getString(KEY_USER_EMAIL, "")
    }

    fun setUserEmail(email: String?) {
        prefs.edit().putString(KEY_USER_EMAIL, email).apply()
    }

    fun getUserToken(): String? {
        return prefs.getString(KEY_USER_TOKEN, null)
    }

    fun setUserToken(token: String?) {
        prefs.edit().putString(KEY_USER_TOKEN, token).apply()
    }

    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = "dev_" + UUID.randomUUID().toString().replace("-", "").take(12)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun setDeviceId(id: String) {
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
    }

    fun getDeviceName(): String {
        return prefs.getString(KEY_DEVICE_NAME, "${Build.MANUFACTURER} ${Build.MODEL}") ?: "Android Device"
    }

    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    fun setAuthToken(token: String?) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun isPaired(): Boolean {
        return prefs.getBoolean(KEY_IS_PAIRED, false) && !getAuthToken().isNullOrEmpty()
    }

    fun setPaired(paired: Boolean) {
        prefs.edit().putBoolean(KEY_IS_PAIRED, paired).apply()
        _isPairedFlow.value = paired
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun isSetupCompleted(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun updateLastSyncTime(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
        _lastSyncFlow.value = timestamp
    }

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_TOKEN)
            .putBoolean(KEY_IS_PAIRED, false)
            .putBoolean(KEY_ONBOARDING_COMPLETED, false)
            .putBoolean(KEY_SETUP_COMPLETED, false)
            .remove(KEY_LAST_SYNC_TIME)
            .apply()
        _isPairedFlow.value = false
        _lastSyncFlow.value = 0L
    }

    companion object {
        /**
         * No baked-in deployment URL. The server address is entered by the user
         * or delivered via the pairing QR payload. An empty default avoids
         * silently sending device data to a third-party host.
         */
        const val DEFAULT_SERVER_URL = ""

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_TOKEN = "user_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_IS_PAIRED = "is_paired"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_SETUP_COMPLETED = "setup_completed"

        private val HOST_ONLY_REGEX = Regex("^[0-9.]+(?::[0-9]+)?.*$")

        /**
         * Validates and normalizes a user-entered server address.
         *
         * Rules:
         *  - only http:// and https:// are accepted (scheme is inferred when
         *    omitted: bare IPs/localhost → http, other hosts → https);
         *  - userinfo, query strings and fragments are rejected;
         *  - dashboard path segments (/admin/nodes, /admin, /dashboard) and
         *    trailing slashes are stripped so API and WebSocket endpoints are
         *    derived from a clean base without duplicated path segments;
         *  - an empty address is reported as "not configured", not an error.
         */
        fun validateServerUrl(raw: String): ServerUrlValidation {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return ServerUrlValidation(null, "No server configured. Enter the address of your Monitory backend (e.g. https://monitory.example.com).")
            }
            if (trimmed.any { it.isWhitespace() || it == '\u0000' }) {
                return ServerUrlValidation(null, "The server address must not contain spaces.")
            }

            var candidate = trimmed
            val lower = candidate.lowercase()
            val allowedSchemes = listOf("http://", "https://")
            val hasKnownScheme = allowedSchemes.any { lower.startsWith(it) }
            val hasAnyScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(candidate)
            if (hasAnyScheme && !hasKnownScheme) {
                return ServerUrlValidation(null, "Only http:// and https:// server addresses are supported. Remove the unsupported scheme prefix.")
            }
            if (!hasKnownScheme) {
                if (candidate.startsWith("//")) candidate = candidate.removePrefix("//")
                // Infer a scheme for convenience: IP/localhost style targets
                // default to plain http (local/LAN deployments), hostnames to https.
                candidate = if (HOST_ONLY_REGEX.matches(candidate)) {
                    "http://$candidate"
                } else {
                    "https://$candidate"
                }
            }

            val uri = try {
                URI(candidate)
            } catch (_: Exception) {
                return ServerUrlValidation(null, "This is not a valid server address. Use a form like https://monitory.example.com or http://192.168.1.10:3000.")
            }

            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return ServerUrlValidation(null, "Only http:// and https:// server addresses are supported.")
            }
            if (uri.userInfo != null) {
                return ServerUrlValidation(null, "Remove the username:password part from the server address.")
            }
            if (!uri.rawQuery.isNullOrEmpty() || uri.fragment != null) {
                return ServerUrlValidation(null, "Remove any ?query or #fragment from the server address.")
            }
            val host = uri.host
            if (host.isNullOrBlank()) {
                return ServerUrlValidation(null, "No host found in the server address.")
            }
            if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) {
                return ServerUrlValidation(null, "The port must be between 1 and 65535.")
            }

            // Strip dashboard page paths and trailing slashes so callers can
            // safely append API/WS route segments without duplicating paths.
            var path = uri.path ?: ""
            path = path
                .replace(Regex("/admin/nodes/?$"), "")
                .replace(Regex("/admin/?$"), "")
                .replace(Regex("/dashboard/?$"), "")
                .trimEnd('/')
            if (path.contains("//")) {
                return ServerUrlValidation(null, "The server address path contains an empty segment ('//').")
            }

            val port = if (uri.port != -1) ":${uri.port}" else ""
            val normalized = "$scheme://$host$port$path".removeSuffix("/")
            return ServerUrlValidation(normalized, null)
        }

        /** API base URL for Retrofit (always ends with '/'). */
        fun deriveApiBaseUrl(normalized: String): String =
            if (normalized.endsWith("/")) normalized else "$normalized/"

        /** Device WebSocket URL derived from the configured HTTP(S) base URL. */
        fun deriveWsDeviceUrl(normalized: String): String {
            val base = normalized.trimEnd('/')
            val wsBase = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                base.startsWith("ws://") || base.startsWith("wss://") -> base
                else -> "ws://$base"
            }
            return "$wsBase/ws/device"
        }
    }
}
