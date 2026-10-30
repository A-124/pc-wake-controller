package com.pcwake.app.net

import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.Validation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PairingPayload(
    val v: Int = 1,
    val kind: String,
    val name: String? = null,
    val host: String,
    val port: Int,
    val fingerprint: String? = null,
    val code: String? = null,
) {
    val mode: ConnectionMode? get() = ConnectionMode.fromWire(kind)
}

object PairingPayloadParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Result<PairingPayload> {
        val text = raw.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("Pairing payload is empty"))
        val dto = runCatching { json.decodeFromString<PairingPayload>(text) }
            .getOrElse { return Result.failure(IllegalArgumentException("Not a PC Wake pairing QR/payload")) }
        if (dto.v != 1) return Result.failure(IllegalArgumentException("Unsupported pairing payload version"))
        val mode = ConnectionMode.fromWire(dto.kind)
            ?: return Result.failure(IllegalArgumentException("Unknown server kind '${dto.kind}'"))
        Validation.validateHost(dto.host)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (!Validation.isValidPort(dto.port)) {
            return Result.failure(IllegalArgumentException("Pairing payload has an invalid port"))
        }
        val fingerprint = dto.fingerprint?.lowercase()?.replace(":", "")?.trim()?.takeIf { it.isNotEmpty() }
        if (fingerprint != null && !Regex("^[0-9a-f]{64}$").matches(fingerprint)) {
            return Result.failure(IllegalArgumentException("Pairing payload has an invalid certificate fingerprint"))
        }
        val normalizedCode = dto.code?.takeIf { it.isNotBlank() }?.let {
            if (Validation.validatePairingCode(it) != null) {
                return Result.failure(IllegalArgumentException("Pairing payload has an invalid pairing code"))
            }
            it
        }
        return Result.success(
            dto.copy(
                kind = mode.wire,
                host = dto.host.trim().removeSurrounding("[", "]"),
                fingerprint = fingerprint,
                code = normalizedCode,
            ),
        )
    }
}
