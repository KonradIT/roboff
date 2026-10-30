package com.roboff.lan.discovery
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Set
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.ResolveListener
 /** Foreground-only mDNS, passive LAN broadcasts, and a bounded Wi-Fi greeting fallback.  */
 class LanDiscovery (context:Context?, listener:com.roboff.lan.discovery.LanDiscovery.Listener?) : java.lang.AutoCloseable {
     open interface Listener {
         open fun onDevice(device:DiscoveredDevice?)
         open fun onMessage(message:kotlin.String?)
    }
    private val nsd:NsdManager?
    private val context:Context?
    private val wifi:WifiManager?
    private val listener:com.roboff.lan.discovery.LanDiscovery.Listener?
    private val debug:Boolean
    private val main:Handler? = Handler(Looper.getMainLooper())
    private var session:com.roboff.lan.discovery.LanDiscovery.Session? = null
    init  {
         var app: Context? = context!!.getApplicationContext()
        this.context = app
        this.nsd = app!!.getSystemService(Context.NSD_SERVICE) as NsdManager?
        this.wifi = app!!.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        this.listener = listener
        this.debug = (app!!.getApplicationInfo().flags and ApplicationInfo.FLAG_DEBUGGABLE) !== 0
    }
     /** Idempotent. Call stop when the screen stops to release multicast and sockets.  */
     fun start() {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            main!!.post({this.start()})
            return 
        }
        if (session != null)return 
         var current: com.roboff.lan.discovery.LanDiscovery.Session? = com.roboff.lan.discovery.LanDiscovery.Session()
        session = current
        try  {
            if (wifi != null) {
                current!!.lock = wifi.createMulticastLock("roboff-discovery")
                current!!.lock!!.setReferenceCounted(false)
                current!!.lock!!.acquire()
            }
        }catch (error:java.lang.RuntimeException) {
            message(current, "Wi-Fi multicast is unavailable. Check the Wi-Fi connection.")
        }
        if (nsd != null) {
            for (serviceType:kotlin.String in LanDiscovery.Companion.SERVICE_TYPES!!)  {
                 var browser: NsdManager.DiscoveryListener? = browser(current)
                current!!.browsers!!.add(browser)
                try  {
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, browser)
                }catch (error:java.lang.RuntimeException) {
                    current!!.browsers!!.remove(browser)
                    message(current, "mDNS discovery is unavailable; listening for LAN broadcasts.")
                }
            }
        }
         var thread: java.lang.Thread? = java.lang.Thread(java.lang.Runnable ({listen(current)}), "roboff-lan-discovery")
        thread!!.setDaemon(true)
        thread!!.start()
        current!!.probeStarter = java.lang.Runnable ({
        if (session != current || !current!!.seen!!.isEmpty())return@Runnable 
        current!!.probe = WifiSubnetProbe.Companion.start(context, java.util.function.Consumer<DiscoveredDevice?> ({device:DiscoveredDevice? -> main!!.post({emit(current, device)})}))
        current!!.probeStopper = java.lang.Runnable ({if (current!!.probe != null) {
            current!!.probe!!.close()
            current!!.probe = null
        }})
        main!!.postDelayed(current!!.probeStopper!!, 40000)
        })
        main!!.postDelayed(current!!.probeStarter!!, 5000)
    }
     fun stop() {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            main!!.post({this.stop()})
            return 
        }
         var old: com.roboff.lan.discovery.LanDiscovery.Session? = session
        if (old == null)return 
        session = null
        old.running = false
        if (old.probeStarter != null)main!!.removeCallbacks(old.probeStarter!!)
        if (old.probeStopper != null)main!!.removeCallbacks(old.probeStopper!!)
        if (old.probe != null) {
            old.probe!!.close()
            old.probe = null
        }
        synchronized(old, {old.socket?.close()})
        old.resolveQueue!!.clear()
        if (nsd != null) {
            for (browser:NsdManager.DiscoveryListener? in java.util.ArrayList<NsdManager.DiscoveryListener?>(old.browsers!!))  {
                try  {
                    nsd.stopServiceDiscovery(browser)
                }catch (ignored:java.lang.RuntimeException) { /* Already stopped by the framework. */}
            }
        }
        old.browsers!!.clear()
        val lock = old.lock
        if (lock != null && lock.isHeld()) lock.release()
    }
    public override fun close() {
        stop()
    }
    private fun browser(current:com.roboff.lan.discovery.LanDiscovery.Session?): NsdManager.DiscoveryListener? {
        return object : NsdManager.DiscoveryListener {
             override fun onDiscoveryStarted(type:kotlin.String?) {}
             override fun onDiscoveryStopped(type:kotlin.String?) {}
             override fun onServiceLost(info:NsdServiceInfo?) {}
             override fun onStartDiscoveryFailed(type:kotlin.String?, error:Int) {
                main!!.post({
                current!!.browsers!!.remove(this)
                message(current, "mDNS browse could not start (" + error + "); LAN broadcast discovery remains active.")
                })
            }
             override fun onStopDiscoveryFailed(type:kotlin.String?, error:Int) {}
             override fun onServiceFound(info:NsdServiceInfo?) {
                main!!.post({
                if (session != current)return@post 
                 var name: kotlin.String? = info!!.getServiceName()
                if (name == null || !LanDiscovery.Companion.isRobotName(name))return@post 
                 var key: kotlin.String? = name + "|" + info!!.getServiceType()
                if (current!!.resolved!!.add(key)) {
                    current!!.resolveQueue!!.add(info)
                    resolveNext(current)
                }
                })
            }
        }
    }
    @kotlin.Suppress("deprecation") private fun resolveNext(current:com.roboff.lan.discovery.LanDiscovery.Session?) {
        if ((session != current) || current!!.resolving || current!!.resolveQueue!!.isEmpty())return 
         var found: NsdServiceInfo? = current!!.resolveQueue!!.poll()
        current!!.resolving = true
        try  {
            nsd!!.resolveService(found!!, object : NsdManager.ResolveListener {
                 override fun onResolveFailed(info:NsdServiceInfo?, error:Int) {
                    main!!.post({
                    if (session != current)return@post 
                    current!!.resolving = false
                    resolveNext(current)
                    })
                }
                 override fun onServiceResolved(info:NsdServiceInfo?) {
                    main!!.post({
                    if (session != current)return@post 
                    current!!.resolving = false
                    if (info!!.getHost() != null) {
                         var host: kotlin.String? = info!!.getHost().getHostAddress()
                        if (LanAddress.isLocalLiteral(host)) {
                             var id: kotlin.String? = LanDiscovery.Companion.attribute(info, "duid")
                            if (id!!.isEmpty())id = LanDiscovery.Companion.attribute(info, "deviceid")
                                                            // _miio advertises its UDP port; Roborock's control port is TCP 58867.
                            emit(current, DiscoveredDevice(id!!, info!!.getServiceName()!!, host!!, 
                            58867, "mDNS", ""))
                        }
                    }
                    resolveNext(current)
                    })
                }
            })
        }catch (error:java.lang.RuntimeException) {
            current!!.resolving = false
            resolveNext(current)
        }
    }
    private fun listen(current:com.roboff.lan.discovery.LanDiscovery.Session?) {
        // Robot advertisements are IPv4 broadcasts. Select AF_INET explicitly instead
        // of relying on the platform's IPv6 dual-stack wildcard broadcast handling.
    try  {
            DatagramChannel.open(StandardProtocolFamily.INET).use({channel -> channel.socket().use({
            socket -> socket.setReuseAddress(true)
            socket.setBroadcast(true)
            socket.bind(InetSocketAddress(InetAddress.getByAddress(ByteArray(4)), 58866))
            socket.setSoTimeout(1000)
            synchronized(current!!, {
            if (!current!!.running)return 
            current!!.socket = socket
            })
             var buffer: ByteArray? = ByteArray(4096)
            while (current!!.running) {
                 var packet: DatagramPacket? = DatagramPacket(buffer, buffer!!.size)
                try  {
                    socket.receive(packet)
                    if (debug)Log.d("RoboffDiscovery", "Received LAN discovery datagram (" + packet!!.getLength() + " bytes)")
                     var device: DiscoveredDevice? = BroadcastDecoder.decode(packet!!.getData(), 
                    packet!!.getLength(), packet!!.getAddress().getHostAddress())
                    main!!.post({emit(current, device)})
                }catch (ignored:SocketTimeoutException) {
                    // Wake periodically so lifecycle cancellation always finishes promptly.
                }catch (error:IOException) {
                    if (current!!.running)message(current, "LAN discovery stopped: " + error.message)
                    break
                }catch (error:java.lang.Exception) {
                    // Malformed or unrelated datagrams must not interrupt valid robot discovery.
                if (debug)Log.d("RoboffDiscovery", "Rejected LAN discovery datagram: " + error.javaClass.getSimpleName())
                }
            }
            })})
        }catch (error:IOException) {
            if (current!!.running)message(current, 
            "Cannot listen for robot broadcasts. Check Wi-Fi and retry discovery.")
        }catch (error:java.lang.SecurityException) {
            if (current!!.running)message(current, 
            "Cannot listen for robot broadcasts. Check Wi-Fi and retry discovery.")
        }
    }
    private fun emit(current:com.roboff.lan.discovery.LanDiscovery.Session?, device:DiscoveredDevice?) {
        if (session != current)return 
         var fingerprint: kotlin.String? = device!!.id + "|" + device!!.source + "|" + device!!.name + "|" + device!!.protocol
        if ((fingerprint == current!!.seen!!.put(device!!.host, fingerprint)))return 
        listener!!.onDevice(device)
    }
    private fun message(current:com.roboff.lan.discovery.LanDiscovery.Session?, message:kotlin.String?) {
        main!!.post({if (session == current)listener!!.onMessage(message)})
    }
    private class Session () {
        @kotlin.concurrent.Volatile 
         var running:Boolean = true
         var socket:DatagramSocket? = null
         var lock:WifiManager.MulticastLock? = null
         var resolving:Boolean = false
         var probeStarter:java.lang.Runnable? = null
         var probeStopper:java.lang.Runnable? = null
         var probe:WifiSubnetProbe? = null
         val browsers:kotlin.collections.MutableList<NsdManager.DiscoveryListener?>? = java.util.ArrayList<NsdManager.DiscoveryListener?>()
         val resolveQueue:ArrayDeque<NsdServiceInfo?>? = ArrayDeque<NsdServiceInfo?>()
         val resolved:kotlin.collections.MutableSet<kotlin.String?>? = HashSet<kotlin.String?>()
         val seen:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? = HashMap<kotlin.String?, kotlin.String?>()
    }
     companion object {
        private val SERVICE_TYPES: Array<String> = arrayOf("_miio._udp.", "_roborock._tcp.")
        private fun isRobotName(name:kotlin.String?): Boolean {
             var lower: kotlin.String? = name!!.lowercase()
            return (lower!!.contains("roborock") || lower!!.contains("rockrobo")
             || lower!!.contains("robo-rock"))
        }
        private fun attribute(info:NsdServiceInfo?, key:kotlin.String?): kotlin.String? {
             var value: ByteArray? = info!!.getAttributes().get(key)
            return if (value == null)"" else String(value, java.nio.charset.StandardCharsets.UTF_8)
        }
    }
}
