package com.roboff.lan.protocol
import com.roboff.lan.discovery.LanAddress
import com.roboff.lan.discovery.HelloProbeCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.HashSet
import java.util.Set
import java.util.concurrent.ThreadFactory
import java.util.function.IntUnaryOperator
import java.util.zip.GZIPInputStream
import javax.net.SocketFactory
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
 /**
 * Direct TCP client. connect/request block and must run off Android's main thread.
 * Requests are correlated independently; timed-out actions are never replayed.
 * close() immediately cancels connect/read/request without taking the connect lock.
 */
 class LocalClient private constructor(host:kotlin.String?, localKey:kotlin.String?, socketFactory:SocketFactory?, port:Int, testAddress:InetAddress?) : Closeable {
     open interface Listener {
         /** Called on the socket reader thread. Post UI changes to the main thread.  */
         open fun onPush(dataPoints:JSONObject?) {}
         /** Called on a worker thread after a previously ready socket disconnects.  */
         open fun onDisconnected(reason:IOException?) {}
    }
     /** A firmware error means the device replied; it is distinct from a timeout.  */
     class RpcException internal constructor(code:Int, message:kotlin.String?) : IOException(message) {
         val code:Int
        init  {
            this.code = code
        }
    }
    private val host:kotlin.String?
    private val localKey:kotlin.String?
    private val socketFactory:SocketFactory?
    private val port:Int
    private val testAddress:InetAddress?
    private val connectLock:Any? = Any()
    private val lifecycle:AtomicLong? = AtomicLong()
    private val rpcCounter:AtomicInteger? = AtomicInteger(10000 + java.security.SecureRandom().nextInt(22768))
    private val sequenceCounter:AtomicInteger? = AtomicInteger(100000 + java.security.SecureRandom().nextInt(900000))
    private val random:java.security.SecureRandom? = java.security.SecureRandom()
    @kotlin.concurrent.Volatile 
    private var connection:com.roboff.lan.protocol.LocalClient.Connection? = null
    @kotlin.concurrent.Volatile 
    private var listener:com.roboff.lan.protocol.LocalClient.Listener? = null
    @kotlin.concurrent.Volatile 
    private var protocolVersion:kotlin.String? = "1.0"
    @kotlin.concurrent.Volatile 
    private var closed:Boolean = false
    @kotlin.jvm.JvmOverloads 
     constructor(host:kotlin.String?, localKey:kotlin.String?, socketFactory:SocketFactory? = SocketFactory.getDefault()) : this(host, localKey, socketFactory, LocalClient.Companion.PORT, null)
     // Isolated loopback transport tests inject an address. Public clients always validate the LAN literal.
    internal constructor(address:InetAddress?, localKey:kotlin.String?, socketFactory:SocketFactory?, port:Int) : this(address!!.getHostAddress(), localKey, socketFactory, port, address)
    init  {
        if (localKey == null || localKey.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size != 16) {
            throw java.lang.IllegalArgumentException("The vacuum's local key must contain exactly 16 UTF-8 bytes.")
        }
        if (socketFactory == null)throw java.lang.IllegalArgumentException("Socket factory is required.")
        this.host = host
        this.localKey = localKey
        this.socketFactory = socketFactory
        this.port = port
        this.testAddress = testAddress
    }
     fun setListener(listener:com.roboff.lan.protocol.LocalClient.Listener?) {
        this.listener = listener
    }
     fun isConnected(): Boolean {
         var current: com.roboff.lan.protocol.LocalClient.Connection? = connection
        return (current != null) && current.ready && !current.socket!!.isClosed()
    }
     fun getProtocolVersion(): kotlin.String? {
        return protocolVersion
    }
    @kotlin.Throws(IOException::class) fun connect() {
        synchronized(connectLock!!, {
        if (closed)throw IOException("This client has been closed.")
        if (isConnected())return 
         val address: InetAddress?
        try  {
            address = if (testAddress != null)testAddress else LanAddress.requireLocalLiteral(host)
        }catch (e:java.lang.IllegalArgumentException) {
            throw IOException(e.message, e)
        }
         var generation: Long = lifecycle!!.get()
         var firstFailure: IOException? = null
         var diagnostics: java.lang.StringBuilder? = java.lang.StringBuilder()
         var first: kotlin.String? = protocolVersion
         var attempts: kotlin.Array<kotlin.String?>? = arrayOf<kotlin.String?>(first, if ((first == "1.0"))"L01" else "1.0")
        for (version:kotlin.String? in attempts!!)  {
            if (closed || lifecycle!!.get() != generation)throw IOException("Connection cancelled.")
             var current: com.roboff.lan.protocol.LocalClient.Connection? = Connection(socketFactory!!.createSocket(), version)
            connection = current
            try  {
                    // close() can now cancel a TCP connect, including while Wi-Fi has no Internet.
            if (closed || lifecycle!!.get() != generation)throw IOException("Connection cancelled.")
                current!!.socket!!.connect(InetSocketAddress(address, port), LocalClient.Companion.TIMEOUT_MS)
                current!!.socket!!.setTcpNoDelay(true)
                current!!.output = current!!.socket!!.getOutputStream()
                 var reader: java.lang.Thread? = java.lang.Thread(java.lang.Runnable ({readLoop(current)}), "roboff-lan-reader")
                reader!!.setDaemon(true)
                reader!!.start()
                 var sequence: Int = 1 // Both the ESP32 and Python reference start hello at sequence 1.
                 var hello: com.roboff.lan.protocol.PacketCodec.Packet? = com.roboff.lan.protocol.PacketCodec.Packet(version, sequence.toLong(), 
                current!!.codec!!.connectNonce, LocalClient.Companion.timestamp(), 0, null)
                 var response: com.roboff.lan.protocol.PacketCodec.Packet? = exchange(current, hello, 
                "raw:1:" + sequence, 5000) as com.roboff.lan.protocol.PacketCodec.Packet?
                if (!(version == response!!.version))throw IOException("Vacuum selected an unsupported local protocol.")
                synchronized(current, {
                if (closed || (lifecycle!!.get() != generation) || (connection != current) || current!!.finished || current!!.socket!!.isClosed()) {
                    throw IOException("Connection cancelled.")
                }
                protocolVersion = version
                current!!.keepAlive = Executors.newSingleThreadScheduledExecutor(ThreadFactory ({
                r:java.lang.Runnable? -> var thread: java.lang.Thread? = java.lang.Thread(r, "roboff-lan-keepalive")
                thread!!.setDaemon(true)
                thread
                }))
                current!!.ready = true
                current!!.keepAlive!!.scheduleWithFixedDelay(java.lang.Runnable ({ping(current)}), 10, 10, TimeUnit.SECONDS)
                })
                return 
            }catch (e:IOException) {
                if (firstFailure == null)firstFailure = e
                if (diagnostics!!.length > 0)diagnostics!!.append("; ")
                diagnostics!!.append(version).append(": ").append(e.message)
                finish(current, e)
            }
        }
        if (closed || lifecycle!!.get() != generation)throw IOException("Connection cancelled.")
        throw IOException(("Could not complete a LAN handshake. Check Wi-Fi and the vacuum's address. ("
         + diagnostics + ")"), firstFailure)
        })
    }
     /** Params must be a JSONArray or JSONObject. No cloud methods are exposed.  */
    @kotlin.jvm.JvmOverloads @kotlin.Throws(IOException::class) fun request(method:kotlin.String?, params:Any? = JSONArray()): Any? {
         var params: Any? = params
        if (closed)throw IOException("This client has been closed.")
        if (!LocalClient.Companion.METHODS!!.contains(method))throw IOException("This method is not supported by the offline client.")
        if (params == null)params = JSONArray()
        if (!(params is JSONArray) && !(params is JSONObject)) {
            throw IOException("Command parameters must be a JSON array or object.")
        }
        connect()
         var current: com.roboff.lan.protocol.LocalClient.Connection? = connection
        if (current == null || !current.ready)throw IOException("Vacuum disconnected.")
         var id: Int = nextRpcId()
         var time: Long = LocalClient.Companion.timestamp()
        try  {
             var inner: JSONObject? = JSONObject().put("id", id).put("method", method).put("params", params)
             var outer: JSONObject? = JSONObject().put("dps", JSONObject().put("101", inner.toString())).put("t", time)
             var packet: com.roboff.lan.protocol.PacketCodec.Packet? = com.roboff.lan.protocol.PacketCodec.Packet(current.version, nextSequence().toLong(), 
            nonce(), time, 4, outer.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            return exchange(current, packet, "rpc:" + id, LocalClient.Companion.TIMEOUT_MS)
        }catch (e:JSONException) {
            throw IOException("Unable to encode local command.", e)
        }
    }
    public override fun close() {
        closed = true
        lifecycle!!.incrementAndGet()
         var current: com.roboff.lan.protocol.LocalClient.Connection? = connection
        if (current != null)finish(current, IOException("Disconnected."))
    }
    private fun nextRpcId(): Int {
        return rpcCounter!!.updateAndGet(IntUnaryOperator ({value:Int -> if (value >= 32767)10000 else value + 1}))
    }
    private fun nextSequence(): Int {
        return sequenceCounter!!.updateAndGet(IntUnaryOperator ({value:Int -> if (value >= 999999)100000 else value + 1}))
    }
    private fun nonce(): Long {
        return 10000L + random!!.nextInt(90000)
    }
    @kotlin.Throws(IOException::class) private fun exchange(current:com.roboff.lan.protocol.LocalClient.Connection?, packet:com.roboff.lan.protocol.PacketCodec.Packet?, key:kotlin.String?, timeout:Int): Any? {
         val future: CompletableFuture<Any?> = CompletableFuture()
        current!!.pending!!.put(key, future)
        try  {
             var encoded: ByteArray = current!!.codec!!.encode(packet)!!
            synchronized(current!!.writeLock!!, {
            if (current!!.socket!!.isClosed()) throw IOException("Vacuum disconnected.")
            val output = current!!.output ?: throw IOException("Vacuum disconnected.")
            output.write(encoded)
            output.flush()
            })
            return future!!.get(timeout.toLong(), TimeUnit.MILLISECONDS)
        }catch (e:TimeoutException) {
             var timeoutError: IOException = IOException(if (packet!!.protocol == 0)"LAN handshake timed out." else "Vacuum did not reply. The command may have run; it was not retried. Check the local key and Wi-Fi.", e)
                        // A handshake may time out while trying an older protocol. connect() cleans it up.
            if (packet!!.protocol != 0)finish(current, timeoutError)
            throw timeoutError
        }catch (e:java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
             var error: IOException = IOException("Local request cancelled.", e)
            finish(current, error)
            throw error
        }catch (e:ExecutionException) {
             var cause: Throwable? = e.cause
            if (cause is IOException)throw cause as IOException
            throw IOException("Local request failed.", cause)
        }catch (e:IOException) {
            if (!(e is RpcException))finish(current, e)
            throw e
        }finally  {
            current!!.pending!!.remove(key, future)
        }
    }
    private fun ping(current:com.roboff.lan.protocol.LocalClient.Connection?) {
        if (current != connection || !current!!.ready)return 
         var sequence: Int = nextSequence()
        try  {
            exchange(current, com.roboff.lan.protocol.PacketCodec.Packet(current!!.version, sequence.toLong(), nonce(), LocalClient.Companion.timestamp(), 2, null), 
            "raw:3:" + sequence, 5000)
        }catch (e:IOException) {
            finish(current, e)
        }
    }
    private fun readLoop(current:com.roboff.lan.protocol.LocalClient.Connection?) {
        try  {
             var input: DataInputStream? = DataInputStream(current!!.socket!!.getInputStream())
            while (!current!!.socket!!.isClosed()) {
                 var length: Int = input!!.readInt()
                if (length < 17 || length > PacketCodec.Companion.MAX_FRAME)throw IOException("Invalid LAN frame size.")
                 var frame: ByteArray = ByteArray(length)
                input!!.readFully(frame) // Retains fragmented TCP data; close() unblocks immediately.
                 var packet: com.roboff.lan.protocol.PacketCodec.Packet? = current!!.codec!!.decode(frame)
                if (!(packet!!.version == current!!.version))throw IOException("Unexpected local protocol version.")
                if (packet!!.protocol == 1)current!!.codec!!.ackNonce = packet!!.random
                if (packet!!.protocol <= 3) {
                    complete(current, "raw:" + packet!!.protocol + ":" + packet!!.sequence, packet)
                    continue 
                }
                                // Real S8 firmware also sends encrypted DPS102 replies under protocol4,
                // after a separate empty protocol5 ACK. Correlate the inner RPC ID.
                if ((packet!!.protocol != 4) && (packet!!.protocol != 5) && (packet!!.protocol != 102))continue 
                                // Empty GENERAL_RESPONSE is a transport ACK; only a DPS102 result with the RPC ID can complete a call.
                if (packet!!.protocol == 5 && packet!!.payload == null)continue 
                 var outer: JSONObject? = JSONObject(String(LocalClient.Companion.uncompress(packet!!.payload)!!, java.nio.charset.StandardCharsets.UTF_8))
                 var dps: JSONObject? = outer!!.optJSONObject("dps")
                if (dps == null)continue 
                 var raw: Any? = dps.opt("102")
                if (raw != null && raw !== JSONObject.NULL) {
                     var rpc: JSONObject? = if (raw is JSONObject)raw as JSONObject? else JSONObject(raw.toString())
                     var key: kotlin.String? = "rpc:" + rpc!!.optInt("id", -1)
                     var future: CompletableFuture<Any?>? = current!!.pending!!.get(key)
                    if (future != null) {
                        if (rpc!!.has("error") && !rpc!!.isNull("error")) {
                             var error: JSONObject? = rpc!!.optJSONObject("error")
                             var code: Int = if (error == null)0 else error.optInt("code", 0)
                             var message: kotlin.String? = if (error == null)"Vacuum rejected the command." else error.optString("message", "Vacuum rejected the command.")
                            future.completeExceptionally(RpcException(code, message))
                        } else if (!rpc!!.has("result") || rpc!!.isNull("result")) {
                            future.completeExceptionally(IOException("Vacuum response did not include a result."))
                        } else {
                             var result: Any? = rpc!!.get("result")
                            if (result is kotlin.String && !("ok" == result)) {
                                 var unsupported: Boolean = ("unknown_method" == result)
                                future.completeExceptionally(RpcException(if (unsupported)-32601 else 0, 
                                if (unsupported)"This vacuum does not support that command." else "Vacuum rejected the command: " + result))
                            } else {
                                future.complete(result)
                            }
                        }
                    }
                }
                 var callback: com.roboff.lan.protocol.LocalClient.Listener? = listener
                if (callback != null && (dps.length() > 1 || !dps.has("102"))) {
                    try  {
                        callback.onPush(dps)
                    }catch (ignored:java.lang.RuntimeException) { /* A UI listener cannot kill transport. */}
                }
            }
        }catch (e:IOException) {
            finish(current, e)
        }catch (e:JSONException) {
            finish(current, IOException("Invalid local response. Check the local key.", e))
        }
    }
    private fun complete(current:com.roboff.lan.protocol.LocalClient.Connection?, key:kotlin.String?, value:Any?) {
         var future: CompletableFuture<Any?>? = current!!.pending!!.get(key)
        if (future != null)future.complete(value)
    }
    private fun finish(current:com.roboff.lan.protocol.LocalClient.Connection?, reason:IOException?) {
         var notify: Boolean
        synchronized(current!!, {
        if (current!!.finished)return 
        current!!.finished = true
        notify = current!!.ready
        current!!.ready = false
        if (connection == current)connection = null
        current!!.keepAlive?.shutdownNow()
        try  {
            current!!.socket!!.close()
        }catch (ignored:IOException) {}
        for (pending:CompletableFuture<Any?>? in current!!.pending!!.values) pending?.completeExceptionally(reason)
        current!!.pending!!.clear()
        })
         var callback: com.roboff.lan.protocol.LocalClient.Listener? = listener
        if (notify && callback != null) {
            try  {
                callback.onDisconnected(reason)
            }catch (ignored:java.lang.RuntimeException) {}
        }
    }
    private inner class Connection internal constructor(socket:Socket?, version:kotlin.String?) {
         val socket:Socket?
         val version:kotlin.String?
         val codec:PacketCodec?
         val writeLock:Any? = Any()
         val pending:ConcurrentHashMap<kotlin.String?, CompletableFuture<Any?>?>? = ConcurrentHashMap<kotlin.String?, CompletableFuture<Any?>?>()
        @kotlin.concurrent.Volatile 
         var ready:Boolean = false
         var finished:Boolean = false
        @kotlin.concurrent.Volatile 
         var output:java.io.OutputStream? = null
        @kotlin.concurrent.Volatile 
         var keepAlive:ScheduledExecutorService? = null
        init  {
            this.socket = socket
            this.version = version
            this.codec = PacketCodec(localKey, (10000 + random!!.nextInt(22768)).toLong())
        }
    }
     companion object {
         val PORT:Int = 58867
        private val TIMEOUT_MS:Int = 7000
        private val METHODS:kotlin.collections.MutableSet<kotlin.String?>? = Collections.unmodifiableSet<kotlin.String?>(HashSet<kotlin.String?>(mutableListOf<kotlin.String?>(
        "get_status", "get_consumable", "get_clean_summary", "get_room_mapping",
        "app_get_init_status", "get_clean_motor_mode", "get_dnd_timer", "get_child_lock_status",
        "get_sound_volume", "get_carpet_mode",
        "app_start", "app_pause", "app_stop", "app_charge", "find_me", "resume_segment_clean",
        "set_dnd_timer", "close_dnd_timer", "set_child_lock_status", "change_sound_volume", "set_carpet_mode",
        "app_rc_start", "app_rc_move", "app_rc_stop", "app_rc_end",
        "set_custom_mode", "set_water_box_custom_mode", "set_mop_mode", "set_clean_motor_mode", "app_segment_clean")))
        private fun timestamp(): Long {
            return java.lang.System.currentTimeMillis() / 1000
        }
        @kotlin.Throws(IOException::class) private fun uncompress(payload:ByteArray?): ByteArray? {
            if (payload == null)throw IOException("Empty local response.")
            if ((payload.size < 2) || ((payload.get(0).toInt() and 255) != 31) || ((payload.get(1).toInt() and 255) != 139))return payload
            GZIPInputStream(ByteArrayInputStream(payload)).use({gzip -> ByteArrayOutputStream().use({
            output -> var buffer: ByteArray? = ByteArray(4096)
             var count: Int
            while ((gzip.read(buffer).also({ count = it })) != -1) {
                if (output.size() + count > PacketCodec.Companion.MAX_FRAME)throw IOException("Local response is too large.")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
            })})
        }
    }
}

