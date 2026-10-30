package com.pcwake.app.net

import com.pcwake.app.core.AgentCommand
import com.pcwake.app.core.AgentStatus
import com.pcwake.app.core.ApiException
import com.pcwake.app.core.ApiSigner
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.PairedServer
import com.pcwake.app.core.PowerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class AgentApi(private val httpFactory: PinnedHttpClientFactory) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class PairResult(
        val credential: PairedServer,
        val server: ServerInfoDto,
        val fingerprint: String,
    )

    suspend fun pair(
        endpoint: String,
        expectedPin: String?,
        code: String,
        deviceName: String,
        deviceModel: String,
        appVersion: String,
        timeoutMs: Long,
    ): PairResult = withContext(Dispatchers.IO) {
        require(Endpoints.isCanonical(endpoint)) { "Invalid HTTPS endpoint" }
        val pin = expectedPin?.takeIf { it.isNotBlank() }?.let(httpFactory::normalizePin)
            ?: httpFactory.capturePin(endpoint, timeoutMs).fingerprint
        val client = httpFactory.pinnedClient(pin, timeoutMs)
        val body = json.encodeToString(
            PairRequestDto.serializer(),
            PairRequestDto(
                code = code,
                deviceName = deviceName,
                deviceModel = deviceModel,
                appVersion = appVersion,
            ),
        ).toRequestBody(JSON)
        val request = Request.Builder()
            .url("$endpoint/api/v1/pair")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(parseError(text) ?: "http_${response.code}", response.code)
            }
            val dto = runCatching { json.decodeFromString(PairResponseDto.serializer(), text) }
                .getOrElse { throw ApiException("bad_response", response.code, "Pairing response was not valid JSON") }
            if (!dto.ok) throw ApiException(dto.error ?: "pair_failed", response.code)
            val server = dto.server
                ?: throw ApiException("bad_response", response.code, "Pairing response missing server info")
            if (server.kind != null && server.kind != ConnectionMode.AGENT.wire) {
                throw ApiException("wrong_server_kind", response.code, "This endpoint is not a Windows agent")
            }
            val deviceId = dto.deviceId?.takeIf { Regex("^[0-9a-f]{32}$").matches(it) }
                ?: throw ApiException("bad_response", response.code, "Invalid device id")
            val token = dto.token?.takeIf { it.length >= 40 }
                ?: throw ApiException("bad_response", response.code, "Invalid device token")
            val credential = PairedServer(
                kind = ConnectionMode.AGENT,
                name = server.name ?: Endpoints.pretty(endpoint),
                endpoint = endpoint,
                deviceId = deviceId,
                pin = pin,
                token = token,
                pairedAt = System.currentTimeMillis(),
            )
            PairResult(credential, server, pin)
        }
    }

    suspend fun status(credential: PairedServer, timeoutMs: Long): AgentStatus {
        val text = signedRequest(credential, "GET", "/api/v1/status", null, timeoutMs)
        val dto = decode<AgentStatusDto>(text)
        if (!dto.ok) throw ApiException(dto.error ?: "status_failed", 200)
        return dto.toModel()
    }

    suspend fun power(
        credential: PairedServer,
        action: PowerAction,
        graceSeconds: Int,
        reason: String?,
        timeoutMs: Long,
    ): AgentCommand {
        require(action != PowerAction.CANCEL) { "Use cancel() for cancellation" }
        val body = json.encodeToString(
            PowerRequestDto.serializer(),
            PowerRequestDto(graceSeconds.coerceIn(0, 60), reason?.take(200)),
        ).toByteArray(Charsets.UTF_8)
        val text = signedRequest(credential, "POST", "/api/v1/power/${action.wire}", body, timeoutMs)
        val dto = decode<PowerResponseDto>(text)
        if (!dto.ok) throw ApiException(dto.error ?: "command_failed", 200)
        return dto.command?.toModel()
            ?: throw ApiException("bad_response", 200, "Server accepted the request but returned no command")
    }

    suspend fun cancel(credential: PairedServer, timeoutMs: Long): Boolean {
        val text = signedRequest(
            credential, "POST", "/api/v1/power/cancel", EMPTY_OBJECT, timeoutMs,
        )
        val dto = decode<PowerResponseDto>(text)
        if (!dto.ok) throw ApiException(dto.error ?: "cancel_failed", 200)
        return dto.cancelled == true
    }

    suspend fun revoke(credential: PairedServer, timeoutMs: Long) {
        val text = signedRequest(credential, "POST", "/api/v1/revoke", EMPTY_OBJECT, timeoutMs)
        val dto = decode<RevokeResponseDto>(text)
        if (!dto.ok) throw ApiException(dto.error ?: "revoke_failed", 200)
    }

    suspend fun capturePin(endpoint: String, timeoutMs: Long): CertificatePin =
        httpFactory.capturePin(endpoint, timeoutMs)

    private suspend fun signedRequest(
        credential: PairedServer,
        method: String,
        path: String,
        body: ByteArray?,
        timeoutMs: Long,
    ): String = withContext(Dispatchers.IO) {
        val actualBody = body ?: ByteArray(0)
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = ApiSigner.newNonce()
        val canonical = ApiSigner.canonical(method, path, timestamp, nonce, actualBody)
        val signature = ApiSigner.sign(credential.token, canonical)
        val builder = Request.Builder()
            .url(credential.endpoint + path)
            .header(ApiSigner.HEADER_DEVICE, credential.deviceId)
            .header(ApiSigner.HEADER_TIMESTAMP, timestamp)
            .header(ApiSigner.HEADER_NONCE, nonce)
            .header(ApiSigner.HEADER_SIGNATURE, signature)
        if (method == "GET") {
            builder.get()
        } else {
            builder.post(actualBody.toRequestBody(JSON))
        }
        val client = httpFactory.pinnedClient(credential.pin, timeoutMs)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(parseError(text) ?: "http_${response.code}", response.code)
            }
            if (text.isBlank()) throw ApiException("empty_response", response.code)
            text
        }
    }

    private inline fun <reified T> decode(text: String): T =
        runCatching { json.decodeFromString<T>(text) }
            .getOrElse {
                if (it is IOException) throw it
                throw ApiException("bad_response", 200, "Server returned an unexpected response")
            }

    private fun parseError(text: String): String? =
        runCatching { json.decodeFromString(ApiErrorDto.serializer(), text).error }.getOrNull()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY_OBJECT = "{}".toByteArray(Charsets.UTF_8)
    }
}
