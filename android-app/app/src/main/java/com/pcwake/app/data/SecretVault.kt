package com.pcwake.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.PairedServer
import com.pcwake.app.core.ServerSummary
import com.pcwake.app.core.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest store for pairing tokens and certificate pins.
 *
 * Only ciphertext (AES-256-GCM, key held by Android Keystore) is written to
 * SharedPreferences. Pairing tokens, certificate pins and device IDs never
 * appear as plaintext on disk or in logcat.
 */
class SecretVault(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pcwake.vault.v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredCredential(
        val kind: String,
        val name: String,
        val endpoint: String,
        val deviceId: String,
        val pin: String,
        val token: String,
        val pairedAt: Long,
    )

    @Serializable
    private data class VaultIndex(val servers: List<ServerSummaryDto> = emptyList())

    @Serializable
    private data class ServerSummaryDto(
        val kind: String,
        val name: String,
        val endpoint: String,
        val pairedAt: Long,
        val fingerprint: String,
    )

    suspend fun save(server: PairedServer) = withContext(Dispatchers.IO) {
        val credential = StoredCredential(
            kind = server.kind.wire,
            name = server.name,
            endpoint = server.endpoint,
            deviceId = server.deviceId,
            pin = server.pin,
            token = server.token,
            pairedAt = server.pairedAt,
        )
        prefs.edit()
            .putString(credentialKey(server.kind, server.endpoint), encrypt(json.encodeToString(credential)))
            .putString(KEY_INDEX, encrypt(json.encodeToString(updatedIndex(server))))
            .commit()
        Unit
    }

    suspend fun get(kind: ConnectionMode, endpoint: String): PairedServer? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(credentialKey(kind, endpoint), null) ?: return@withContext null
        val stored = runCatching { json.decodeFromString<StoredCredential>(decrypt(raw)) }.getOrNull()
            ?: return@withContext null
        PairedServer(
            kind = ConnectionMode.fromWire(stored.kind) ?: return@withContext null,
            name = stored.name,
            endpoint = stored.endpoint,
            deviceId = stored.deviceId,
            pin = stored.pin,
            token = stored.token,
            pairedAt = stored.pairedAt,
        )
    }

    suspend fun listServers(): List<ServerSummary> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_INDEX, null) ?: return@withContext emptyList()
        val index = runCatching { json.decodeFromString<VaultIndex>(decrypt(raw)) }.getOrNull()
            ?: return@withContext emptyList()
        index.servers.mapNotNull { dto ->
            val kind = ConnectionMode.fromWire(dto.kind) ?: return@mapNotNull null
            ServerSummary(kind, dto.name, dto.endpoint, dto.pairedAt, dto.fingerprint)
        }.sortedBy { it.pairedAt }
    }

    suspend fun has(kind: ConnectionMode, endpoint: String): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(credentialKey(kind, endpoint))
    }

    suspend fun delete(kind: ConnectionMode, endpoint: String) = withContext(Dispatchers.IO) {
        val index = readIndex()
        val filtered = index.servers.filterNot { it.kind == kind.wire && it.endpoint == endpoint }
        prefs.edit().apply {
            remove(credentialKey(kind, endpoint))
            if (filtered.isEmpty()) remove(KEY_INDEX)
            else putString(KEY_INDEX, encrypt(json.encodeToString(VaultIndex(filtered))))
        }.commit()
        Unit
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
        runCatching { keyStore()?.deleteEntry(KEY_ALIAS) }
        Unit
    }

    private fun readIndex(): VaultIndex {
        val raw = prefs.getString(KEY_INDEX, null) ?: return VaultIndex()
        return runCatching { json.decodeFromString<VaultIndex>(decrypt(raw)) }.getOrDefault(VaultIndex())
    }

    private fun updatedIndex(server: PairedServer): VaultIndex {
        val current = readIndex().servers.filterNot {
            it.kind == server.kind.wire && it.endpoint == server.endpoint
        }
        return VaultIndex(
            current + ServerSummaryDto(
                kind = server.kind.wire,
                name = server.name,
                endpoint = server.endpoint,
                pairedAt = server.pairedAt,
                fingerprint = server.pin,
            ),
        )
    }

    private fun credentialKey(kind: ConnectionMode, endpoint: String): String =
        "cred." + sha256Hex(kind.wire + "|" + endpoint)

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = VERSION
        iv.copyInto(combined, 1)
        ciphertext.copyInto(combined, 1 + iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > 1 + IV_SIZE) { "Corrupted secure vault" }
        val version = combined[0]
        require(version.toInt() == VERSION.toInt()) { "Unsupported vault version" }
        val iv = combined.copyOfRange(1, 1 + IV_SIZE)
        val ciphertext = combined.copyOfRange(1 + IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore() ?: error("Android Keystore unavailable")
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pcwake_vault_aes_v1"
        const val KEY_INDEX = "index"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val VERSION: Byte = 1
    }
}
