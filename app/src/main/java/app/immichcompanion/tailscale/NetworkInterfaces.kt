package app.immichcompanion.tailscale

import java.net.NetworkInterface

/**
 * Describes the device's interfaces in the line format that `Tsbridge.setInterfaces`
 * parses. Android blocks netlink for apps, so Go can't list interfaces itself.
 */
internal object NetworkInterfaces {
    fun describe(): String = buildString {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (nif in ifaces) {
            val addrs = nif.interfaceAddresses
            val broadcast = addrs.any { it.broadcast != null }
            append("${nif.name} ${nif.index} ${nif.mtu} ${nif.isUp} $broadcast ")
            append("${nif.isLoopback} ${nif.isPointToPoint} ${nif.supportsMulticast()} |")
            for (a in addrs) {
                val host = a.address.hostAddress?.substringBefore('%') ?: continue
                append(" $host/${a.networkPrefixLength}")
            }
            append('\n')
        }
    }
}
