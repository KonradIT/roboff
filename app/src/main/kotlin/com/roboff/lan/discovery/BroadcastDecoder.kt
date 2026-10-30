package com.roboff.lan.discovery

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Arrays
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decodes Roborock's passive UDP discovery datagrams (1.0 and L01). */
object BroadcastDecoder {
    private val key = "qWKYcdQWrbm9hPqe".toByteArray(StandardCharsets.US_ASCII)

    @Throws(Exception::class)
    fun decode(packet: ByteArray, length: Int, sender: String): DiscoveredDevice {
        LanAddress.requireLocalLiteral(sender)
        require(length >= 31 && length <= packet.size && length <= 4096) {
            "Invalid discovery packet length"
        }
        val version = String(packet, 0, 3, StandardCharsets.US_ASCII)
        require(version == "1.0" || version == "L01") { "Unsupported discovery version" }
        val payloadLength = ((packet[9].toInt() and 255) shl 8) or (packet[10].toInt() and 255)
        require(payloadLength >= 16 && payloadLength + 15 == length) { "Invalid discovery payload length" }

        val crc = CRC32().apply { update(packet, 0, length - 4) }
        var expected = 0L
        for (index in length - 4 until length) expected = (expected shl 8) or (packet[index].toInt() and 255).toLong()
        require(crc.value == expected) { "Discovery checksum mismatch" }

        val plain = if (version == "L01") {
            val digest = MessageDigest.getInstance("SHA-256")
            val derivedKey = digest.digest(key)
            val nonce = Arrays.copyOf(digest.digest(packet.copyOfRange(0, 9)), 12)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, nonce))
                doFinal(packet, 11, payloadLength)
            }
        } else {
            if (payloadLength % 16 != 0) throw GeneralSecurityException("Invalid AES block length")
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                doFinal(packet, 11, payloadLength)
            }
        }

        val json = JSONObject(String(plain, StandardCharsets.UTF_8))
        val duid = json.getString("duid")
        require(duid.isNotEmpty() && duid.length <= 128) { "Invalid robot ID" }
        val advertised = json.getString("ip")
        require(LanAddress.requireLocalLiteral(advertised) == LanAddress.requireLocalLiteral(sender)) {
            "Discovery address does not match its sender"
        }
        return DiscoveredDevice(duid, "Roborock", sender, 58867, "LAN broadcast", version)
    }
}
