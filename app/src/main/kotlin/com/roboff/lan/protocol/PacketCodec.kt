package com.roboff.lan.protocol
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
 /** Roborock 1.0 and L01 LAN framing, matching python-roborock's local encoder.  */
internal class PacketCodec (localKey:kotlin.String?, connectNonce:Long) {
    private val localKey:ByteArray?
     val connectNonce:Long
    @kotlin.concurrent.Volatile 
     var ackNonce:Long? = null
    init  {
        this.localKey = localKey!!.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        this.connectNonce = connectNonce
    }
    internal class Packet (version:kotlin.String?, sequence:Long, random:Long, timestamp:Long, protocol:Int, payload:ByteArray?) {
         val version:kotlin.String?
         val sequence:Long
         val random:Long
         val timestamp:Long
         val protocol:Int
         val payload:ByteArray?
        init  {
            this.version = version
            this.sequence = sequence
            this.random = random
            this.timestamp = timestamp
            this.protocol = protocol
            this.payload = payload
        }
    }
    @kotlin.Throws(IOException::class) fun encode(packet:com.roboff.lan.protocol.PacketCodec.Packet?): ByteArray? {
        try  {
             var payload: ByteArray? = if (packet!!.payload == null)null else crypt(packet, packet!!.payload, Cipher.ENCRYPT_MODE)
             var buffer: ByteArrayOutputStream? = ByteArrayOutputStream()
             var output: DataOutputStream? = DataOutputStream(buffer)
            output!!.write(packet!!.version!!.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
            output!!.writeInt(packet!!.sequence.toInt())
            output!!.writeInt(packet!!.random.toInt())
            output!!.writeInt(packet!!.timestamp.toInt())
            output!!.writeShort(packet!!.protocol)
            if (payload != null) {
                if (payload.size > 65535)throw IOException("Request exceeds LAN packet limit.")
                output!!.writeShort(payload.size)
                output!!.write(payload)
            }
             var crc: CRC32? = CRC32()
            crc!!.update(buffer!!.toByteArray())
            output!!.writeInt(crc!!.getValue().toInt())
             var framed: ByteArrayOutputStream? = ByteArrayOutputStream()
             var stream: DataOutputStream? = DataOutputStream(framed)
            stream!!.writeInt(buffer!!.size())
            buffer!!.writeTo(stream)
            return framed!!.toByteArray()
        }catch (e:GeneralSecurityException) {
            throw IOException("Unable to encrypt local command.", e)
        }
    }
    @kotlin.Throws(IOException::class) fun decode(frame:ByteArray?): com.roboff.lan.protocol.PacketCodec.Packet? {
        if (frame!!.size < 17 || frame!!.size > PacketCodec.Companion.MAX_FRAME)throw IOException("Invalid LAN frame size.")
         var buffer: java.nio.ByteBuffer? = java.nio.ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
         var versionBytes: ByteArray? = ByteArray(3)
        buffer!!.get(versionBytes)
         var version: kotlin.String? = String(versionBytes!!, java.nio.charset.StandardCharsets.US_ASCII)
        if (!(version == "1.0") && !(version == "L01"))throw IOException("Unsupported local protocol.")
         var sequence: Long = PacketCodec.Companion.unsigned(buffer!!.getInt())
         var random: Long = PacketCodec.Companion.unsigned(buffer!!.getInt())
         var timestamp: Long = PacketCodec.Companion.unsigned(buffer!!.getInt())
         var protocol: Int = buffer!!.getShort().toInt() and 65535
        if (protocol <= 3) {
            if (!PacketCodec.Companion.emptyFrame(frame, true))throw IOException("Malformed local handshake.")
            return com.roboff.lan.protocol.PacketCodec.Packet(version, sequence, random, timestamp, protocol, null)
        }
        if (protocol == 5 && PacketCodec.Companion.emptyFrame(frame, false)) {
            // Some firmware acknowledges a request before sending its encrypted DPS102 result.
            // The empty ACK is only a transport hint, never an authenticated RPC result.
        return com.roboff.lan.protocol.PacketCodec.Packet(version, sequence, random, timestamp, protocol, null)
        }
        if (frame!!.size < 23)throw IOException("Truncated local response.")
         var length: Int = buffer!!.getShort().toInt() and 65535
        if (length == 0 || 19 + length + 4 != frame!!.size)throw IOException("Invalid local payload length.")
        PacketCodec.Companion.verifyCrc(frame, 19 + length)
         var encrypted: ByteArray? = ByteArray(length)
        buffer!!.get(encrypted)
         var packet: com.roboff.lan.protocol.PacketCodec.Packet? = com.roboff.lan.protocol.PacketCodec.Packet(version, sequence, random, timestamp, protocol, null)
        try  {
            return com.roboff.lan.protocol.PacketCodec.Packet(version, sequence, random, timestamp, protocol, crypt(packet, encrypted, Cipher.DECRYPT_MODE))
        }catch (e:GeneralSecurityException) {
            throw IOException("Local authentication failed. Check this vacuum's local key.", e)
        }
    }
    @kotlin.Throws(GeneralSecurityException::class, IOException::class) private fun crypt(packet:com.roboff.lan.protocol.PacketCodec.Packet?, input:ByteArray?, mode:Int): ByteArray? {
         var inputKey: ByteArrayOutputStream? = ByteArrayOutputStream()
         var timestamp: kotlin.String? = kotlin.String.format(Locale.ROOT, "%08x", packet!!.timestamp)
         var order: IntArray? = intArrayOf(5, 6, 3, 7, 1, 2, 0, 4)
        for (position:Int in order!!) inputKey!!.write(timestamp!!.get(position).code)
        inputKey!!.write(localKey, 0, localKey!!.size)
        inputKey!!.write(PacketCodec.Companion.SALT, 0, PacketCodec.Companion.SALT!!.size)
        if ((packet!!.version == "1.0")) {
             var key: ByteArray? = MessageDigest.getInstance("MD5").digest(inputKey!!.toByteArray())
             var cipher: Cipher? = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher!!.init(mode, SecretKeySpec(key, "AES"))
            return cipher!!.doFinal(input)
        }
        if (!(packet!!.version == "L01"))throw IOException("Unsupported local protocol.")
         var acknowledgement: Long? = ackNonce
        if (acknowledgement == null)throw IOException("Local handshake has not completed.")
         var key: ByteArray? = MessageDigest.getInstance("SHA-256").digest(inputKey!!.toByteArray())
         var ivInput: java.nio.ByteBuffer? = java.nio.ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        ivInput!!.putInt(packet!!.sequence.toInt()).putInt(packet!!.random.toInt()).putInt(packet!!.timestamp.toInt())
         var iv: ByteArray? = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(ivInput!!.array()), 12)
         var aad: java.nio.ByteBuffer? = java.nio.ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        aad!!.putInt(packet!!.sequence.toInt()).putInt(connectNonce.toInt()).putInt(acknowledgement.toInt())
        .putInt(packet!!.random.toInt()).putInt(packet!!.timestamp.toInt())
         var cipher: Cipher? = Cipher.getInstance("AES/GCM/NoPadding")
        cipher!!.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher!!.updateAAD(aad!!.array())
        return cipher!!.doFinal(input)
    }
     companion object {
         val MAX_FRAME:Int = 1024 * 1024
        private val SALT:ByteArray? = "TXdfu\$jyZ#TZHsg4".toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        private fun unsigned(value:Int): Long {
            return value.toLong() and 0xffffffffL
        }
        @kotlin.Throws(IOException::class) private fun emptyFrame(frame:ByteArray?, control:Boolean): Boolean {
        // Empty control replies have no checksum in python-roborock. The real S8 sends
        // header+00000000, which must not be mistaken for a failed CRC. Encrypted frames
        // always take the separate length/CRC/authentication path below.
        if (frame!!.size == 17)return true
            if (frame!!.size == 21) {
                if (!control && !PacketCodec.Companion.zeroTrailer(frame, 17))PacketCodec.Companion.verifyCrc(frame, 17)
                return true
            }
             var zeroLength: Boolean = (frame!!.size == 19 || frame!!.size == 23) && (frame!!.get(17).toInt() == 0) && (frame!!.get(18).toInt() == 0)
            if (!zeroLength)return false
            if ((frame!!.size == 23) && !control && !PacketCodec.Companion.zeroTrailer(frame, 19))PacketCodec.Companion.verifyCrc(frame, 19)
            return true
        }
        private fun zeroTrailer(frame:ByteArray?, offset:Int): Boolean {
            return (frame!!.get(offset).toInt() == 0) && (frame!!.get(offset + 1).toInt() == 0) && (frame!!.get(offset + 2).toInt() == 0) && (frame!!.get(offset + 3).toInt() == 0)
        }
        @kotlin.Throws(IOException::class) private fun verifyCrc(frame:ByteArray?, length:Int) {
             var crc: CRC32? = CRC32()
            crc!!.update(frame, 0, length)
             var expected: Long = PacketCodec.Companion.unsigned(java.nio.ByteBuffer.wrap(frame, length, 4).getInt())
            if (crc!!.getValue() != expected)throw IOException("Local response checksum failed.")
        }
    }
}
