package com.shizulu.manager

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WirelessAdbRunner(private val context: Context) {
    fun test(pairingCode: String, pairingPort: Int): WirelessAdbResult {
        require(pairingCode.isNotBlank()) { "Pairing code is required." }
        require(pairingPort > 0) { "Pairing port is required." }

        val log = StringBuilder()
        val adb = connect(pairingCode, pairingPort, log)
        adb.use {
            val response = adb.shell("id; getprop ro.product.model")
            log.append("exit=").append(response.exitCode).append('\n')
            log.append(response.allOutput.trim())
        }
        return WirelessAdbResult(log.toString().trim())
    }

    fun run(moduleId: String, action: ShizuleAction, pairingCode: String, pairingPort: Int): WirelessAdbResult {
        require(pairingCode.isNotBlank()) { "Pairing code is required." }
        require(pairingPort > 0) { "Pairing port is required." }

        val output = StringBuilder()
        val adb = connect(pairingCode, pairingPort, output)
        adb.use {
            output.append('\n')
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

    fun runCommand(moduleId: String, command: String, pairingCode: String, pairingPort: Int): WirelessAdbResult {
        val action = ShizuleAction(
            id = "direct",
            label = "Direct command",
            commands = listOf(ShizuleCommand(command))
        )
        return run(moduleId, action, pairingCode, pairingPort)
    }

    private fun connect(pairingCode: String, pairingPort: Int, log: StringBuilder): Kadb {
        connectUsingCachedOrDiscoveredPort(log)?.let { return it }

        log.append("No reusable Wireless ADB connection found; trying fresh pairing.\n")
        pair(pairingCode, pairingPort, log)
        Thread.sleep(PAIRING_SETTLE_MS)

        connectUsingCachedOrDiscoveredPort(log)?.let { return it }
        error("Could not connect to Wireless ADB. Use a fresh pairing code and the pairing port from Android's Wireless debugging dialog, then try again.")
    }

    private fun connectUsingCachedOrDiscoveredPort(log: StringBuilder): Kadb? {
        val tried = mutableSetOf<Int>()
        cachedConnectPort().takeIf { it > 0 }?.let { cachedPort ->
            tried += cachedPort
            tryConnect(cachedPort, "cached", log)?.let { return it }
        }

        val discoveredPort = discoverConnectPort(log)
        if (discoveredPort != null && tried.add(discoveredPort)) {
            tryConnect(discoveredPort, "discovered", log)?.let { return it }
        }

        scanAndConnect(tried, log)?.let { return it }
        return null
    }

    private fun scanAndConnect(tried: MutableSet<Int>, log: StringBuilder): Kadb? {
        log.append("Auto-scan probing ")
            .append(ADB_HOST)
            .append(" ports ")
            .append(PORT_SCAN_START)
            .append('-')
            .append(PORT_SCAN_END)
            .append(".\n")

        val openPorts = scanOpenLocalPorts(tried)
        if (openPorts.isEmpty()) {
            log.append("Auto-scan did not find open local ports.\n")
            return null
        }

        log.append("Auto-scan found open port(s): ")
            .append(openPorts.formatPortList())
            .append('\n')

        openPorts.forEach { port ->
            if (tried.add(port)) {
                tryConnect(port, "auto-scan", log)?.let { return it }
            }
        }

        log.append("Auto-scan found open port(s), but none completed the ADB handshake.\n")
        return null
    }

    private fun scanOpenLocalPorts(excludedPorts: Set<Int>): List<Int> {
        val openPorts = Collections.synchronizedList(mutableListOf<Int>())
        val nextPort = AtomicInteger(PORT_SCAN_START)
        val latch = CountDownLatch(PORT_SCAN_WORKERS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PORT_SCAN_TOTAL_TIMEOUT_MS)
        val executor = Executors.newFixedThreadPool(PORT_SCAN_WORKERS)

        repeat(PORT_SCAN_WORKERS) {
            executor.execute {
                try {
                    while (System.nanoTime() < deadline) {
                        val port = nextPort.getAndIncrement()
                        if (port > PORT_SCAN_END) break
                        if (port in excludedPorts) continue
                        if (isLocalPortOpen(port)) openPorts.add(port)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(PORT_SCAN_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        executor.shutdownNow()
        return openPorts.sorted()
    }

    private fun isLocalPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ADB_HOST, port), PORT_SCAN_CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
    }

    private fun tryConnect(port: Int, source: String, log: StringBuilder): Kadb? {
        val adb = Kadb.tryConnection(ADB_HOST, port)
        return if (adb != null) {
            saveConnectPort(port)
            log.append("Connected to Wireless ADB on ")
                .append(ADB_HOST)
                .append(':')
                .append(port)
                .append(" using ")
                .append(source)
                .append(" trust.\n")
            adb
        } else {
            log.append("Wireless ADB ").append(source).append(" port ")
                .append(port)
                .append(" was not reachable.\n")
            null
        }
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

    private fun discoverConnectPort(log: StringBuilder): Int? {
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
                log.append("Could not resolve Wireless ADB service ")
                    .append(serviceInfo.serviceName)
                    .append(" (")
                    .append(errorCode)
                    .append(").\n")
                resolving.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                foundPort.set(serviceInfo.port)
                saveConnectPort(serviceInfo.port)
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
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log.append("Wireless ADB discovery failed to start (").append(errorCode).append(").\n")
                latch.countDown()
            }
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

        return foundPort.get().also { port ->
            if (port == null) {
                log.append("Could not find Wireless ADB connect service. Keep Wireless debugging enabled and try again.\n")
            }
        }
    }

    private fun cachedConnectPort(): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ADB_CONNECT_PORT, 0)
    }

    private fun saveConnectPort(port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ADB_CONNECT_PORT, port)
            .apply()
    }

    private fun String.wrapForShizulu(moduleId: String): String {
        val module = moduleId.shellQuote()
        val command = shellQuote()
        return "SHIZULU=1 SHIZULU_API_VERSION=1 SHIZULU_MODULE_ID=$module /system/bin/sh -c $command"
    }

    private fun String.shellQuote(): String {
        return "'${replace("'", "'\"'\"'")}'"
    }

    private fun List<Int>.formatPortList(): String {
        val shown = take(MAX_REPORTED_SCAN_PORTS)
        return if (size <= MAX_REPORTED_SCAN_PORTS) {
            shown.joinToString(", ")
        } else {
            shown.joinToString(", ") + ", ... (+${size - shown.size} more)"
        }
    }

    companion object {
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_CONNECT_SERVICE = "_adb-tls-connect._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        private const val PAIRING_SETTLE_MS = 700L
        private const val PORT_SCAN_START = 30_000
        private const val PORT_SCAN_END = 49_999
        private const val PORT_SCAN_WORKERS = 48
        private const val PORT_SCAN_CONNECT_TIMEOUT_MS = 18
        private const val PORT_SCAN_TOTAL_TIMEOUT_MS = 6_500L
        private const val MAX_REPORTED_SCAN_PORTS = 10
        private const val PREFS = "shizulu_settings"
        private const val KEY_ADB_CONNECT_PORT = "adb_connect_port"
    }
}

data class WirelessAdbResult(val output: String)
