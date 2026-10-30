package com.roboff.lan.discovery

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** Validates numeric private LAN addresses without resolving hostnames. */
object LanAddress {
    fun isLocalLiteral(host: String?): Boolean = try {
        requireLocalLiteral(host)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    fun requireLocalLiteral(host: String?): InetAddress {
        require(!host.isNullOrEmpty() && host == host.trim()) {
            "Enter the vacuum's private LAN IP address."
        }
        val address = try {
            if (host.contains(':')) {
                val numeric = host.substringBefore('%')
                require(numeric.matches(Regex("[0-9a-fA-F:]+")) && !host.contains('[') && !host.contains(']')) {
                    "Enter a numeric private LAN address."
                }
                InetAddress.getByName(host)
            } else {
                val parts = host.split('.', ignoreCase = false, limit = 5)
                require(parts.size == 4) { "Enter a numeric private LAN address." }
                val bytes = ByteArray(4)
                for (index in parts.indices) {
                    require(parts[index].matches(Regex("[0-9]{1,3}"))) {
                        "Enter a numeric private LAN address."
                    }
                    val value = parts[index].toInt()
                    require(value <= 255) { "Invalid IPv4 address." }
                    bytes[index] = value.toByte()
                }
                InetAddress.getByAddress(bytes)
            }
        } catch (error: UnknownHostException) {
            throw IllegalArgumentException("Enter a valid private LAN IP address.", error)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("Enter a valid private LAN IP address.", error)
        }

        val bytes = address.address
        val uniqueLocalV6 = address is Inet6Address && (bytes[0].toInt() and 0xfe) == 0xfc
        val local = address.isSiteLocalAddress || address.isLinkLocalAddress || uniqueLocalV6
        val broadcastV4 = address is Inet4Address && (bytes[3].toInt() and 0xff) == 255
        require(local && !broadcastV4 && !address.isAnyLocalAddress &&
            !address.isLoopbackAddress && !address.isMulticastAddress
        ) { "Only private LAN addresses are allowed." }
        return address
    }
}
