package com.pcwake.app.data

import android.content.Context
import android.net.Uri
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.MacAddress
import com.pcwake.app.core.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupEnvelope(
    val v: Int = 1,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int = BackupCrypto.PBKDF2_ITERATIONS,
    val cipher: String = "AES-256-GCM",
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

@Serializable
data class BackupDevice(
    val name: String,
    val macAddress: String,
    val localIp: String? = null,
    val broadcastAddress: String,
    val port: Int = 9,
    val agentEndpoint: String? = null,
    val bridgeEndpoint: String? = null,
)

@Serializable
data class BackupConfig(val devices: List<BackupDevice> = emptyList())

data class ImportResult(val imported: Int, val skipped: Int)

object BackupCrypto {
    const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): BackupEnvelope {
        require(passphrase.size >= 8) { "Backup passphrase must be at least 8 characters" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        val enc = Base64.getEncoder()
        return BackupEnvelope(
            salt = enc.encodeToString(salt),
            iv = enc.encodeToString(cipher.iv),
            ciphertext = enc.encodeToString(ciphertext),
        )
    }

    fun decrypt(envelope: BackupEnvelope, passphrase: CharArray): ByteArray {
        require(envelope.v == 1) { "Unsupported backup version" }
        require(envelope.iterations in 100_000..1_000_000) { "Unreasonable KDF parameters" }
        val dec = Base64.getDecoder()
        val salt = dec.decode(envelope.salt)
        val iv = dec.decode(envelope.iv)
        require(iv.size == IV_BYTES) { "Invalid backup IV" }
        val key = deriveKey(passphrase, salt, envelope.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(dec.decode(envelope.ciphertext))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * Encrypted export/import of PC profiles. Tokens and certificate pins are
 * intentionally excluded; a restored server connection must be paired again.
 */
class BackupManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val historyRepository: HistoryRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun exportConfig(uri: Uri, passphrase: CharArray): Int = withContext(Dispatchers.IO) {
        val devices = deviceRepository.getAllWithConnections()
        val config = BackupConfig(
            devices = devices.map { it.toBackupDevice() },
        )
        val envelope = BackupCrypto.encrypt(
            json.encodeToString(BackupConfig.serializer(), config).toByteArray(Charsets.UTF_8),
            passphrase,
        )
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Cannot open the selected file for writing")
        output.use { it.write(json.encodeToString(BackupEnvelope.serializer(), envelope).toByteArray()) }
        devices.size
    }

    suspend fun importConfig(uri: Uri, passphrase: CharArray): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Cannot read the selected file")
        val envelope = json.decodeFromString(BackupEnvelope.serializer(), text)
        val decrypted = BackupCrypto.decrypt(envelope, passphrase)
        val config = json.decodeFromString(BackupConfig.serializer(), decrypted.toString(Charsets.UTF_8))
        var imported = 0
        var skipped = 0
        val valid = mutableListOf<DeviceRepository.DeviceInput>()
        config.devices.forEach { candidate ->
            val mac = MacAddress.parseOrNull(candidate.macAddress)
            val validName = Validation.validateName(candidate.name) == null
            val validPort = Validation.isValidPort(candidate.port)
            val validBroadcast = Validation.validateBroadcast(candidate.broadcastAddress) == null
            val validAgent = candidate.agentEndpoint == null || candidate.agentEndpoint.startsWith("https://")
            val validBridge = candidate.bridgeEndpoint == null || candidate.bridgeEndpoint.startsWith("https://")
            if (mac == null || !validName || !validPort || !validBroadcast || !validAgent || !validBridge) {
                skipped++
            } else {
                valid += DeviceRepository.DeviceInput(
                    name = candidate.name.trim(),
                    macAddress = mac.canonical,
                    localIp = candidate.localIp?.takeIf(Validation::isValidIpv4),
                    broadcastAddress = candidate.broadcastAddress.trim(),
                    port = candidate.port,
                    agentEndpoint = candidate.agentEndpoint,
                    bridgeEndpoint = candidate.bridgeEndpoint,
                )
                imported++
            }
        }
        deviceRepository.importDevices(valid)
        ImportResult(imported = imported, skipped = skipped)
    }

    suspend fun exportHistoryCsv(uri: Uri): Int = withContext(Dispatchers.IO) {
        val csv = historyRepository.exportCsv()
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Cannot open the selected file for writing")
        output.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
        csv.lineSequence().count() - 1
    }
}

private fun DeviceWithConnections.toBackupDevice(): BackupDevice = BackupDevice(
    name = device.name,
    macAddress = device.macAddress,
    localIp = device.localIp,
    broadcastAddress = device.broadcastAddress,
    port = device.port,
    agentEndpoint = connection(ConnectionMode.AGENT)?.endpoint,
    bridgeEndpoint = connection(ConnectionMode.BRIDGE)?.endpoint,
)
