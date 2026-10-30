package com.roboff.lan.discovery
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.InputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.function.Consumer
import java.util.List
import java.util.Set
import java.util.concurrent.ThreadFactory
import javax.net.SocketFactory
 /** A single, bounded sweep of the current private Wi-Fi subnet using only a TCP greeting.  */
internal class WifiSubnetProbe () : java.lang.AutoCloseable {
    private val workers:ExecutorService? = Executors.newFixedThreadPool(8, ThreadFactory ({
    runnable:java.lang.Runnable? -> var thread: java.lang.Thread? = java.lang.Thread(runnable, "roboff-lan-probe")
    thread!!.setDaemon(true)
    thread
    }))
    private val sockets:kotlin.collections.MutableSet<Socket?>? = ConcurrentHashMap.newKeySet<Socket?>()
    private val random:java.security.SecureRandom? = java.security.SecureRandom()
    private val deadline:Long = java.lang.System.nanoTime() + 40000000000L
    @kotlin.concurrent.Volatile 
    private var running:Boolean = true
    private fun inspect(factory:SocketFactory?, peer:InetAddress?, onDevice:java.util.function.Consumer<DiscoveredDevice?>?) {
        for (version:kotlin.String? in arrayOf<kotlin.String?>("1.0", "L01"))  {
            if (!running || java.lang.System.nanoTime() >= deadline)return 
             var connected: Boolean = false
            try  {
                factory!!.createSocket().use({
                socket -> sockets!!.add(socket)
                try  {
                    if (!running)return 
                    socket.connect(InetSocketAddress(peer!!, 58867), 500)
                    connected = true
                    if (!running)return 
                     var sequence: Int = 1 // Firmware and reference clients reserve sequence one for HELLO.
                     var nonce: Int = 10000 + random!!.nextInt(20000)
                    socket.getOutputStream().write(HelloProbeCodec.request(version!!, sequence, nonce, (java.lang.System.currentTimeMillis() / 1000L).toInt()))
                     var negotiated: kotlin.String? = HelloProbeCodec.validateResponse(WifiSubnetProbe.Companion.readResponse(socket), sequence, version)
                    if (running && java.lang.System.nanoTime() < deadline) {
                        onDevice!!.accept(DiscoveredDevice("", "Roborock", peer!!.getHostAddress(), 
                        58867, "LAN probe", negotiated))
                    }
                    return 
                }finally  {
                    sockets!!.remove(socket)
                }
                })
            }catch (ignored:IOException) {
                // Closed/non-Roborock hosts are normal during a subnet sweep.
            if (!connected)return  // Another greeting version cannot fix an unreachable TCP port.
                }catch (ignored:java.lang.IllegalArgumentException) {
                if (!connected)return 
            }
        }
    }
    public override fun close() {
        running = false
        workers!!.shutdownNow()
        for (socket:Socket? in sockets!!)  {
            try  {
                socket!!.close()
            }catch (ignored:IOException) {}
        }
        sockets!!.clear()
    }
     companion object {
         fun start(context:Context?, onDevice:java.util.function.Consumer<DiscoveredDevice?>?): WifiSubnetProbe? {
             var probe: WifiSubnetProbe? = WifiSubnetProbe()
             var manager: ConnectivityManager? = context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            if (manager == null) {
                probe!!.close()
                return probe
            }
            try  {
                for (network:Network? in manager.getAllNetworks())  {
                     var caps: NetworkCapabilities? = manager.getNetworkCapabilities(network)
                    if (((caps == null) || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                     || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))continue 
                     var properties: LinkProperties? = manager.getLinkProperties(network)
                    if (properties == null)continue 
                    for (link:LinkAddress? in properties.getLinkAddresses())  {
                        if (!(link!!.getAddress() is Inet4Address))continue 
                         var peers: kotlin.collections.MutableList<InetAddress?>? = WifiSubnetProbe.Companion.candidates(link!!.getAddress().getHostAddress(), link!!.getPrefixLength())
                        if (peers!!.isEmpty())continue 
                         var factory: SocketFactory? = network!!.getSocketFactory()
                        for (peer:InetAddress? in peers!!)  {
                            probe!!.workers!!.execute(java.lang.Runnable ({probe!!.inspect(factory, peer, onDevice)}))
                        }
                        probe!!.workers!!.shutdown()
                        return probe // One Wi-Fi IPv4 subnet, never every route/interface.
                        }
                }
            }catch (ignored:java.lang.SecurityException) {
            // Foreground discovery may be stopped while its network disappears.
            }catch (ignored:RejectedExecutionException) {}
            probe!!.close()
            return probe
        }
         fun candidates(ownHost:kotlin.String?, prefixLength:Int): kotlin.collections.MutableList<InetAddress?>? {
            if (prefixLength < 0 || prefixLength > 30)return mutableListOf<InetAddress?>()
             val own: InetAddress?
            try  {
                own = LanAddress.requireLocalLiteral(ownHost)
            }catch (ignored:java.lang.IllegalArgumentException) {
                return mutableListOf<InetAddress?>()
            }
            if (!(own is Inet4Address))return mutableListOf<InetAddress?>()
             var bytes: ByteArray? = own.getAddress()
             var first: Int = bytes!!.get(0).toInt() and 255
             var second: Int = bytes!!.get(1).toInt() and 255
            if (!(((first == 10) || ((first == 172) && (second >= 16) && (second <= 31))
             || (first == 192 && second == 168))))return mutableListOf<InetAddress?>()
             var address: Int = java.nio.ByteBuffer.wrap(bytes).getInt()
             var boundedPrefix: Int = java.lang.Math.max(prefixLength, 24)
             var mask: Int = -1 shl (32 - boundedPrefix)
             var start: Long = (address and mask).toLong() and 0xffffffffL
             var end: Long = ((address and mask) or mask.inv()).toLong() and 0xffffffffL
             var hosts: kotlin.collections.MutableList<InetAddress?>? = java.util.ArrayList<InetAddress?>()
            for (current in start + 1 until end)  {
                if (current.toInt() == address)continue 
                try  {
                    hosts!!.add(InetAddress.getByAddress(java.nio.ByteBuffer.allocate(4).putInt(current.toInt()).array()))
                }catch (impossible:IOException) {
                    throw java.lang.IllegalStateException(impossible)
                }
            }
            return hosts
        }
        @kotlin.Throws(IOException::class) private fun readResponse(socket:Socket?): ByteArray {
             var readDeadline: Long = java.lang.System.nanoTime() + 1000000000L
             var prefix: ByteArray = ByteArray(4)
            WifiSubnetProbe.Companion.readFully(socket, prefix, readDeadline)
             var length: Int = java.nio.ByteBuffer.wrap(prefix).getInt()
            if ((length != 17) && (length != 19) && (length != 21) && (length != 23)) {
                throw IOException("Not a Roborock greeting")
            }
             var frame: ByteArray = ByteArray(length)
            WifiSubnetProbe.Companion.readFully(socket, frame, readDeadline)
            return frame
        }
        @kotlin.Throws(IOException::class) private fun readFully(socket:Socket?, data:ByteArray, deadline:Long) {
             var input: java.io.InputStream? = socket!!.getInputStream()
             var offset: Int = 0
            while (offset < data.size) {
                 var left: Long = deadline - java.lang.System.nanoTime()
                if (left <= 0)throw SocketTimeoutException("Greeting timed out")
                socket!!.setSoTimeout(java.lang.Math.max(1, (left + 999999L) / 1000000L).toInt())
                 var count: Int = input!!.read(data, offset, data.size - offset)
                if (count < 0)throw IOException("Greeting closed")
                offset += count
            }
        }
    }
}
