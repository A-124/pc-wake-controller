package com.pcwake.app.core

import java.net.InetAddress

object Validation {

    private val ipv4Regex = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val hostnameRegex = Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")

    fun isValidIpv4(value: String): Boolean {
        val match = ipv4Regex.matchEntire(value.trim()) ?: return false
        return match.groupValues.drop(1).all { part ->
            part.length <= 3 && (part == "0" || !part.startsWith("0")) && part.toInt() in 0..255
        }
    }

    fun parseIpv4(value: String): InetAddress? {
        if (!isValidIpv4(value)) return null
        return runCatching { InetAddress.getByName(value.trim()) }.getOrNull()
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun isValidPort(text: String): Boolean = text.trim().toIntOrNull()?.let(::isValidPort) == true

    /** LAN-facing broadcast address. Accepts 255.255.255.255 or a directed broadcast. */
    fun validateBroadcast(value: String): String? = when {
        value.isBlank() -> "Broadcast address is required"
        !isValidIpv4(value) -> "Enter a valid IPv4 broadcast address, e.g. 192.168.1.255"
        value.trim() == "0.0.0.0" -> "0.0.0.0 is not a usable broadcast address"
        value.trim().startsWith("127.") -> "Loopback cannot wake another PC"
        parseIpv4(value)?.isMulticastAddress == true -> "Multicast addresses cannot be used for Wake-on-LAN"
        else -> null
    }

    fun validateLocalIp(value: String): String? = when {
        value.isBlank() -> null
        !isValidIpv4(value) -> "Enter a valid IPv4 address or leave empty"
        value.trim() == "0.0.0.0" -> "0.0.0.0 is not a device address"
        else -> null
    }

    fun validateHost(value: String): String? {
        val host = value.trim()
        if (host.isBlank()) return "Host is required"
        if (host.contains("://")) return "Enter only the host, without http:// or https://"
        if (host.contains('/')) return "Enter only the host, without a path"
        if (isValidIpv4(host)) return null
        if (host.contains(':') && runCatching { InetAddress.getByName(host) }.isSuccess) return null
        if (!hostnameRegex.matches(host)) return "Enter a valid IPv4, IPv6, or DNS name"
        return null
    }

    fun validateName(value: String): String? {
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> "Name is required"
            trimmed.length > 60 -> "Keep the name under 60 characters"
            trimmed.any { it.isISOControl() } -> "Name contains invalid characters"
            else -> null
        }
    }

    fun validatePairingCode(value: String): String? {
        val normalized = normalizePairingCode(value)
        if (normalized.length != 8) return "Pairing code has 8 characters, e.g. K7Q2-M4XP"
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        if (normalized.any { it !in alphabet }) return "Pairing code contains invalid characters"
        return null
    }

    /** Crockford-style normalization used by the agent and bridge. */
    fun normalizePairingCode(value: String): String {
        val mapped = value.trim().uppercase().map {
            when (it) {
                'I', 'L' -> '1'
                'O' -> '0'
                'U' -> 'V'
                else -> it
            }
        }.filter { it.isLetterOrDigit() }
        return mapped.joinToString("")
    }

    /** Suggests the directed broadcast for a /prefix network. */
    fun broadcastFor(ip: String, prefixLength: Int): String? {
        if (!isValidIpv4(ip) || prefixLength !in 0..32) return null
        val parts = ip.trim().split('.').map { it.toInt() }
        var address = 0L
        parts.forEach { address = (address shl 8) or it.toLong() }
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val broadcast = (address and mask) or (mask.inv() and 0xFFFFFFFFL)
        return listOf(24, 16, 8, 0)
            .map { (broadcast shr it) and 0xFF }
            .joinToString(".")
    }

    fun isPrivateAddress(ip: String?): Boolean {
        val addr = ip?.let(::parseIpv4) ?: return false
        val b = addr.address
        if (b.size != 4) return false
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    fun looksLikePublicHost(host: String): Boolean =
        isValidIpv4(host) && !isPrivateAddress(host)
}
