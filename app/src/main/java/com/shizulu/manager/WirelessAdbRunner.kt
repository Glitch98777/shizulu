package com.shizulu.manager

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WirelessAdbRunner(private val context: Context) {
    fun test(pairingCode: String, pairingPort: Int): WirelessAdbResult {
        require(pairingCode.isNotBlank()) { "Pairing code is required." }
        require(pairingPort > 0) { "Pairing port is required." }

        val log = StringBuilder()
        pair(pairingCode, pairingPort, log)
        val connectPort = discoverConnectPort(log)
        val adb = Kadb.tryConnection(ADB_HOST, connectPort)
            ?: error("Could not connect to Wireless ADB on $ADB_HOST:$connectPort.")
        adb.use {
            val response = adb.shell("id; getprop ro.product.model")
            log.append("Connected to Wireless ADB on ").append(ADB_HOST).append(':').append(connectPort).append('\n')
            log.append("exit=").append(response.exitCode).append('\n')
            log.append(response.allOutput.trim())
        }
        return WirelessAdbResult(log.toString().trim())
    }

    fun run(moduleId: String, action: ShizuleAction, pairingCode: String, pairingPort: Int): WirelessAdbResult {
        require(pairingCode.isNotBlank()) { "Pairing code is required." }
        require(pairingPort > 0) { "Pairing port is required." }

        val output = StringBuilder()
        pair(pairingCode, pairingPort, output)
        val connectPort = discoverConnectPort(output)

        val adb = Kadb.tryConnection(ADB_HOST, connectPort)
            ?: error("Could not connect to Wireless ADB on $ADB_HOST:$connectPort.")
        adb.use {
            output.append("Connected to Wireless ADB on ").append(ADB_HOST).append(':').append(connectPort).append("\n\n")
            action.commands.forEachIndexed { index, command ->
                val wrapped = command.exec.wrapForShizulu(moduleId)
                output.append("$ ").append(command.exec).append('\n')
                val response = adb.shell(wrapped)
                output.append("exit=").append(response.exitCode).append('\n')
                val allOutput = response.allOutput.trim()
                if (allOutput.isNotBlank()) output.append(allOutput).append('\n')
                if (index < action.commands.lastIndex) output.append('\n')
            }
        }

        return WirelessAdbResult(output.toString().trim())
    }

    private fun pair(pairingCode: String, pairingPort: Int, log: StringBuilder) {
        runCatching {
            runBlocking {
                Kadb.pair(ADB_HOST, pairingPort, pairingCode, "Shizulu")
            }
        }
            .onSuccess {
                log.append("Paired with Wireless ADB on ").append(ADB_HOST).append(':').append(pairingPort).append('\n')
            }
            .onFailure {
                log.append("Pairing did not complete: ").append(it.message ?: it.javaClass.simpleName).append('\n')
                log.append("Trying existing Wireless ADB trust anyway.\n")
            }
    }

    private fun discoverConnectPort(log: StringBuilder): Int {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("shizulu-adb-mdns")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val foundPort = AtomicReference<Int?>()
        val resolving = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val discoveryRef = AtomicReference<NsdManager.DiscoveryListener?>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                foundPort.set(serviceInfo.port)
                log.append("Discovered Wireless ADB service ")
                    .append(serviceInfo.serviceName)
                    .append(" on port ")
                    .append(serviceInfo.port)
                    .append('\n')
                latch.countDown()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = latch.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains(ADB_CONNECT_SERVICE)) return
                if (!resolving.compareAndSet(false, true)) return
                nsdManager.resolveService(serviceInfo, resolveListener)
            }
        }
        discoveryRef.set(discoveryListener)

        try {
            nsdManager.discoverServices(ADB_CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            discoveryRef.get()?.let { listener ->
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
            runCatching { multicastLock?.release() }
        }

        return foundPort.get() ?: error("Could not find Wireless ADB connect service. Keep Wireless debugging open and on the same Wi-Fi network, then try again.")
    }

    private fun String.wrapForShizulu(moduleId: String): String {
        val module = moduleId.shellQuote()
        val command = shellQuote()
        return "SHIZULU=1 SHIZULU_API_VERSION=1 SHIZULU_MODULE_ID=$module /system/bin/sh -c $command"
    }

    private fun String.shellQuote(): String {
        return "'${replace("'", "'\"'\"'")}'"
    }

    companion object {
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_CONNECT_SERVICE = "_adb-tls-connect._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
    }
}

data class WirelessAdbResult(val output: String)
