package com.agentpad.workspace.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * 无线调试端口自动发现（mDNS / NSD）。
 *
 * ⚠ 2026-11-04 新增。**照抄 Shizuku `AdbMdns.kt`**（RikkaApps/Shizuku, Apache-2.0）。
 *
 * 为什么必须这么做：
 *   无线调试的端口不是固定的，每开关一次 adbd 都会换。手动填端口必然失效。
 *   但 Android 11+ 的 adbd 会通过 mDNS 广播自己：
 *     - `_adb-tls-pairing._tcp`   配对端口
 *     - `_adb-tls-connect._tcp`   连接端口
 *   监听这两个服务类型就能拿到**当前真实端口**，不用猜也不用扫。
 *
 * 用的是系统自带的 `android.net.nsd.NsdManager`，**零额外依赖**。
 * （Shizuku 也是这么做的，没有引 jmdns。）
 *
 * 关键细节（照抄 Shizuku 的坑）：
 *  - `isPortAvailable(port)` 反转判断：本地 bind 成功说明端口没被占 → 返回 false，
 *    即 **bind 失败才认为是 adbd 在监听** —— 这点原版注释没写，但代码就是这么写的。
 *  - 还要校验解析出的 host 属于本机网卡，避免拿到局域网里别人的 adb。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val onPort: (Int) -> Unit,
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager? = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            runCatching {
                nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
    }

    private fun onDiscoveryStart() { registered = true }
    private fun onDiscoveryStop() { registered = false }

    private fun onServiceFound(info: NsdServiceInfo) {
        runCatching { nsdManager?.resolveService(info, ResolveListener(this)) }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) onPort(-1)
    }

    private fun onServiceResolved(resolved: NsdServiceInfo) {
        if (!running) return
        val host = resolved.host?.hostAddress ?: return
        val port = resolved.port
        // 校验 host 必须是本机网卡地址，过滤掉局域网里其它设备的广播
        val isLocal = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
                runCatching {
                    nif.inetAddresses.asSequence().any { it.hostAddress == host }
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
        if (!isLocal) return
        // 端口必须「被占用」才说明是 adbd 在监听（照抄 Shizuku 的 isPortAvailable 反转逻辑）
        if (!isPortAvailable(port)) {
            serviceName = resolved.serviceName
            onPort(port)
        }
    }

    /** true = 端口空闲（没有服务监听）；false = 已被占用（大概率是 adbd）。 */
    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            true
        }
    } catch (_: IOException) {
        false
    }

    internal class DiscoveryListener(private val owner: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = owner.onDiscoveryStart()
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStopped(serviceType: String) = owner.onDiscoveryStop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) = owner.onServiceFound(serviceInfo)
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = owner.onServiceLost(serviceInfo)
    }

    internal class ResolveListener(private val owner: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) =
            owner.onServiceResolved(nsdServiceInfo)
    }

    companion object {
        /** 连接端口（无线调试主页显示的那个）。 */
        const val TLS_CONNECT = "_adb-tls-connect._tcp"

        /** 配对端口（「使用配对码配对设备」弹窗里显示的）。 */
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    }
}