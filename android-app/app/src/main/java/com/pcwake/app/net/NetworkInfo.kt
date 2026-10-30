package com.pcwake.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.pcwake.app.core.Validation
import java.net.Inet4Address

data class NetworkSnapshot(
    val transport: String,
    val interfaceName: String?,
    val localIpv4: String?,
    val prefixLength: Int?,
    val broadcast: String?,
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val metered: Boolean,
)

class NetworkInfoProvider(private val context: Context) {

    fun snapshot(): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        val link: LinkProperties? = network?.let(cm::getLinkProperties)
        val transport = when {
            caps == null -> "No active network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
        val linkAddress = link?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        }
        val ip = (linkAddress?.address as? Inet4Address)?.hostAddress
        val prefix = if (ip != null) linkAddress?.prefixLength else null
        val broadcast = if (ip != null && prefix != null) {
            Validation.broadcastFor(ip, prefix)
        } else {
            null
        }
        return NetworkSnapshot(
            transport = transport,
            interfaceName = link?.interfaceName,
            localIpv4 = ip,
            prefixLength = prefix,
            broadcast = broadcast,
            isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
        )
    }

    fun activeInterfaceName(): String? = snapshot().interfaceName
}
