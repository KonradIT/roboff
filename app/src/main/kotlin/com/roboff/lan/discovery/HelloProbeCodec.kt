package com.roboff.lan.discovery

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** Keyless transport greeting only; this codec cannot encode vacuum commands. */
internal object HelloProbeCodec {
    fun request(version: String, sequence: Int, nonce: Int, timestamp: Int): ByteArray {
        require(version == "1.0" || version == "L01") { "Unknown greeting version" }
        val packet = ByteBuffer.allocate(25)
        packet.putInt(21).put(version.toByteArray(StandardCharsets.US_ASCII))
        packet.putInt(sequence).putInt(nonce).putInt(timestamp).putShort(0)
        val checksum = CRC32().apply { update(packet.array(), 4, 17) }
        packet.putInt(checksum.value.toInt())
        return packet.array()
    }

    @Throws(IOException::class)
    fun validateResponse(frame: ByteArray, sequence: Int, expectedVersion: String): String {
        val length = frame.size
        if (length !in listOf(17, 19, 21, 23)) throw IOException("Invalid greeting length")
        val header = ByteBuffer.wrap(frame)
        val version = ByteArray(3).also(header::get).toString(StandardCharsets.US_ASCII)
        if (version != expectedVersion || (version != "1.0" && version != "L01")) {
            throw IOException("Invalid greeting version")
        }
        if (header.int != sequence) throw IOException("Uncorrelated greeting")
        header.int // Robot nonce is used only after authentication.
        header.int // Robot timestamp.
        if ((header.short.toInt() and 65535) != 1) throw IOException("Not a Roborock HELLO_RESPONSE")
        if ((length == 19 || length == 23) && (frame[17] != 0.toByte() || frame[18] != 0.toByte())) {
            throw IOException("Unexpected greeting payload")
        }
        return version
    }
}
