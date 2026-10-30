package com.pcwake.app.core

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

class ApiException(
    val errorCode: String,
    val httpStatus: Int,
    message: String = errorCode,
    cause: Throwable? = null,
) : Exception(message, cause)

class PinMismatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NotPairedException(message: String) : Exception(message)

object ErrorText {
    fun humanize(error: Throwable): String = when (error) {
        is PinMismatchException -> error.message ?: "Certificate changed"
        is ApiException -> when (error.errorCode) {
            "unauthorized" -> "Authentication failed. Re-pair this server."
            "invalid_code" -> "The pairing code is wrong or expired."
            "rate_limited" -> "Too many attempts. Wait a minute and try again."
            "command_pending" -> "Another power command is already pending."
            "target_not_allowed" -> "This MAC is not allowed by the Wake Bridge."
            "pairing_closed" -> "Pairing is not open. Start pairing on the Windows agent or bridge first."
            "not_found" -> "The server does not expose this endpoint. Check the agent/bridge version."
            else -> "Server rejected the request (${error.errorCode})."
        }
        is SSLPeerUnverifiedException -> "Certificate pin mismatch — the server certificate changed. Re-pair after verifying the PC."
        is CertificateException -> "Certificate verification failed. Check the fingerprint and try again."
        is SSLException -> "Secure connection failed: ${error.message ?: "TLS error"}"
        is SocketTimeoutException -> "Timed out. The PC or bridge may be offline, asleep, or unreachable."
        is UnknownHostException -> "Host not found. Check the address and your network."
        else -> error.message ?: "Unexpected network error."
    }
}

fun Throwable.isCertProblem(): Boolean =
    this is PinMismatchException ||
        this is CertificateException ||
        this is SSLPeerUnverifiedException ||
        (this is SSLException && (message?.contains("pin", ignoreCase = true) == true ||
            message?.contains("certificate", ignoreCase = true) == true))
