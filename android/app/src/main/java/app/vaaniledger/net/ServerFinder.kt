package app.vaaniledger.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/**
 * Find the Vaani server on this Wi-Fi, so pairing needs only the code.
 *
 * The server announces a `_vaani._tcp` service over multicast DNS (see
 * backend/app/discovery.py); Android's NsdManager listens for it. Typing
 * "http://192.168.29.3:8000/" correctly was the step people got wrong, and
 * the IP changes whenever the router hands out a new one.
 *
 * [onFound] is called on the main thread with a base URL ending in "/",
 * at most once. [stop] must be called when the screen closes.
 */
class ServerFinder(context: Context, private val onFound: (url: String, name: String) -> Unit) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var done = false
    private var listening = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listening = false }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (done || !service.serviceType.contains(TYPE.removeSuffix("."))) return
            resolve(service)
        }
    }

    fun start() {
        runCatching {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            listening = true
        }
    }

    fun stop() {
        done = true
        if (listening) runCatching { nsd.stopServiceDiscovery(discovery) }
        listening = false
    }

    @Suppress("DEPRECATION") // the replacement needs API 34; this works on every version we ship to
    private fun resolve(service: NsdServiceInfo) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                if (done || host.contains(':')) return   // IPv6 link-local needs a scope id; skip it
                done = true
                val url = "http://$host:${info.port}/"
                main.post { onFound(url, info.serviceName) }
            }
        })
    }

    companion object {
        const val TYPE = "_vaani._tcp."
    }
}
