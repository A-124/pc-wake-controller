package com.pcwake.app.net

import com.pcwake.app.core.Validation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Endpoints {
    const val AGENT_PORT = 47101
    const val BRIDGE_PORT = 8443

    /** Builds and validates a canonical HTTPS endpoint (no path, no credentials). */
    fun build(host: String, port: Int): Result<String> {
        Validation.validateHost(host)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (!Validation.isValidPort(port)) {
            return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
        }
        val cleanHost = host.trim().removeSurrounding("[", "]")
        val url = runCatching {
            okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host(cleanHost)
                .port(port)
                .build()
        }.getOrElse {
            return Result.failure(IllegalArgumentException("Invalid host address"))
        }
        return Result.success(url.toString().trimEnd('/'))
    }

    fun hostAndPort(endpoint: String): Pair<String, Int>? {
        val url = endpoint.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.encodedPath != "/") return null
        return url.host to url.port
    }

    fun isCanonical(endpoint: String): Boolean {
        if (!endpoint.startsWith("https://")) return false
        val url = endpoint.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.query == null &&
            url.fragment == null &&
            (url.encodedPath == "/" || url.encodedPath.isEmpty())
    }

    fun pretty(endpoint: String): String = endpoint.removePrefix("https://").trimEnd('/')
}
