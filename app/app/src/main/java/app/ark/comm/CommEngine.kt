package app.ark.comm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import app.ark.sensors.MyFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 附近的方舟设备。 */
data class Neighbor(val id: Int, val nick: String, val addr: String, val rssi: Int, val lastSeen: Long, val device: BluetoothDevice)

/**
 * 蓝牙消息引擎（单例）：BLE 广播 + 扫描 + GATT 服务端（收消息）+ GATT 客户端（发消息）+ 接力。
 * 协议见 [CommProto]。界面或 [CommService] 调 [start]/[stop]；两者之一在跑就保持在线。
 *
 * 扫描占空比：界面开着 = 每 12 秒扫 5 秒；后台“保持在线” = 每 30 秒扫 5 秒；
 * 60 秒内刚出现新邻居则临时加密到每 10 秒扫 5 秒。广播常开（低功耗档约每秒 1 包）。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object CommEngine {
    private const val SCAN_MS = 5_000L
    private const val NEIGHBOR_TTL_MS = 10 * 60_000L
    private const val BACKFILL_WINDOW_S = 6 * 3600L
    private const val RETRY_MS = 60_000L

    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val _neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    val neighbors: StateFlow<List<Neighbor>> = _neighbors
    /** 本次运行收到的新消息数（前台服务通知用）。 */
    val incoming = MutableStateFlow(0)
    val scanning = MutableStateFlow(false)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var adv: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var server: BluetoothGattServer? = null
    private var ackChar: BluetoothGattCharacteristic? = null
    private val subscribed = ConcurrentHashMap<String, Boolean>()
    private val reassemblers = ConcurrentHashMap<String, CommProto.Reassembler>()

    private var wanted = false
    private var background = false   // 后台保持在线（前台服务）在跑
    private var uiOn = false          // 界面开着在用
    /** 界面这一路是否在用（BtChatTab 退到后台时据此决定要不要放掉、回前台时要不要恢复）。 */
    val uiActive: Boolean get() = uiOn
    private var lastNewNeighbor = 0L
    private var scanOn = false
    private var receiverOn = false

    private val neighborMap = LinkedHashMap<Int, Neighbor>()
    private val delivered = ConcurrentHashMap.newKeySet<String>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val unconfirmed = ConcurrentHashMap<String, Int>()

    private class Job(val neighborId: Int, val msg: CommProto.Msg, val key: String, val mine: Boolean)
    private val queue = LinkedBlockingQueue<Job>()
    private var worker: Thread? = null
    @Volatile private var xfer = 0

    // ---------------- 本机身份 ----------------
    private fun prefs() = app.getSharedPreferences("ark", Context.MODE_PRIVATE)

    fun nodeId(ctx: Context): Int {
        val p = ctx.applicationContext.getSharedPreferences("ark", Context.MODE_PRIVATE)
        var id = p.getInt("comm_id", 0)
        if (id == 0) { id = java.util.Random().nextInt(); if (id == 0) id = 1; p.edit().putInt("comm_id", id).apply() }
        return id
    }

    fun nick(ctx: Context): String {
        val p = ctx.applicationContext.getSharedPreferences("ark", Context.MODE_PRIVATE)
        return p.getString("comm_nick", null)?.takeIf { it.isNotBlank() } ?: ("方舟" + CommProto.shortId(nodeId(ctx)))
    }

    fun setNick(ctx: Context, nick: String) {
        ctx.applicationContext.getSharedPreferences("ark", Context.MODE_PRIVATE).edit().putString("comm_nick", CommProto.truncUtf8(nick.trim(), CommProto.NICK_MAX)).apply()
        if (running.value) main.post { restartAdvertising() }
    }

    // ---------------- 权限 / 蓝牙状态 ----------------
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // 旧版：BLUETOOTH/ADMIN 是安装时权限，扫描需要定位权限

    fun missingPermissions(ctx: Context) = requiredPermissions().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(ctx: Context): Boolean = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) { false }

    fun hasBle(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    // ---------------- 启停 ----------------
    /** [fromService]=true 表示后台保持在线；false 表示界面在用。 */
    @Synchronized
    fun start(ctx: Context, fromService: Boolean) {
        app = ctx.applicationContext
        CommStore.load(app)
        if (fromService) background = true else uiOn = true
        wanted = true
        if (running.value) { main.post { applyMode() }; return }
        if (!hasBle(app)) { error.value = "这台手机不支持低功耗蓝牙"; return }
        val miss = missingPermissions(app)
        if (miss.isNotEmpty()) { error.value = "缺少蓝牙权限"; return }
        if (!bluetoothOn(app)) { error.value = "蓝牙没有打开：请到系统设置里打开蓝牙（方舟不会替你打开）"; watchBluetooth(); return }
        error.value = null
        watchBluetooth()
        main.post { bringUp() }
    }

    /** 界面或服务其中一个退出；两个都不需要了才真正停。 */
    @Synchronized
    fun release(fromService: Boolean) {
        if (fromService) background = false else uiOn = false
        if (!background && !uiOn) shutdown() else main.post { applyMode() }
    }

    @Synchronized
    private fun shutdown() {
        wanted = false
        if (receiverOn) { runCatching { app.unregisterReceiver(btReceiver) }; receiverOn = false }
        main.post { tearDown() }
    }

    private fun watchBluetooth() {
        if (receiverOn) return
        receiverOn = true
        ContextCompat.registerReceiver(app, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> { main.post { tearDown() }; error.value = "蓝牙被关闭了：打开后会自动恢复" }
                BluetoothAdapter.STATE_ON -> if (wanted && !running.value && missingPermissions(app).isEmpty()) { error.value = null; main.post { bringUp() } }
            }
        }
    }

    private fun bringUp() {
        if (running.value) return
        if (missingPermissions(app).isNotEmpty()) { error.value = "缺少蓝牙权限"; return }
        val mgr = app.getSystemService(BluetoothManager::class.java)
        val ad = mgr?.adapter
        if (ad == null || !bluetoothOn(app)) { error.value = "蓝牙没有打开"; return }
        try {
            adapter = ad
            adv = ad.bluetoothLeAdvertiser
            scanner = ad.bluetoothLeScanner
            openServer(mgr)
        } catch (e: SecurityException) { error.value = "缺少蓝牙权限"; return }
        if (adv == null) error.value = "这台手机不支持蓝牙广播：别人发现不了你，但你仍能发现并发消息给对方"
        running.value = true
        startWorker()
        restartAdvertising()
        main.post(cycle)
    }

    private fun tearDown() {
        main.removeCallbacks(cycle); main.removeCallbacks(stopScanR)
        stopScan()
        runCatching { adv?.stopAdvertising(advCb) }
        runCatching { server?.close() }
        server = null; ackChar = null
        subscribed.clear(); reassemblers.clear()
        worker?.interrupt(); worker = null
        queue.clear(); queued.clear()
        synchronized(neighborMap) { neighborMap.clear() }
        _neighbors.value = emptyList()
        running.value = false
    }

    // ---------------- 广播 ----------------
    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { error.value = "蓝牙广播失败（错误 $errorCode）：别人可能发现不了你" }
    }

    private fun restartAdvertising() {
        val a = adv ?: return
        runCatching { a.stopAdvertising(advCb) }
        if (!running.value) return
        val low = background && !uiOn
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (low) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(CommProto.SERVICE)).build()
        val scanResp = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addManufacturerData(CommProto.COMPANY, CommProto.adv(nodeId(app), nick(app))).build()
        runCatching { a.startAdvertising(settings, data, scanResp, advCb) }.onFailure { error.value = "蓝牙广播失败：${it.message}" }
    }

    private fun applyMode() { restartAdvertising() }

    // ---------------- 扫描（低占空比） ----------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val info = CommProto.parseAdv(r.scanRecord?.getManufacturerSpecificData(CommProto.COMPANY)) ?: return
            if (info.id == nodeId(app)) return
            val now = System.currentTimeMillis()
            val n = Neighbor(info.id, info.nick, r.device.address, r.rssi, now, r.device)
            val isNew: Boolean
            synchronized(neighborMap) {
                isNew = neighborMap[info.id]?.let { now - it.lastSeen > NEIGHBOR_TTL_MS } ?: true
                neighborMap[info.id] = n
                neighborMap.entries.removeAll { now - it.value.lastSeen > NEIGHBOR_TTL_MS * 3 }
                _neighbors.value = neighborMap.values.sortedByDescending { it.rssi }
            }
            if (isNew) lastNewNeighbor = now
            backfill(n)
        }
        override fun onScanFailed(errorCode: Int) { error.value = "蓝牙扫描失败（错误 $errorCode）"; scanOn = false; scanning.value = false }
    }

    private val stopScanR = Runnable { stopScan() }

    private fun startScan() {
        val s = scanner ?: adapter?.bluetoothLeScanner ?: return
        scanner = s
        if (scanOn) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CommProto.SERVICE)).build()
        val st = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { s.startScan(listOf(filter), st, scanCb); scanOn = true; scanning.value = true }.onFailure { error.value = "蓝牙扫描失败：${it.message}" }
    }

    private fun stopScan() {
        if (!scanOn) return
        runCatching { scanner?.stopScan(scanCb) }
        scanOn = false; scanning.value = false
    }

    private val cycle = object : Runnable {
        override fun run() {
            if (!running.value) return
            startScan()
            main.postDelayed(stopScanR, SCAN_MS)
            val base = if (uiOn) 12_000L else 30_000L
            val boost = System.currentTimeMillis() - lastNewNeighbor < 60_000L
            main.postDelayed(this, if (boost) 10_000L else base)
        }
    }

    /** 立刻扫一轮（界面按钮）。 */
    fun scanNow() {
        main.post {
            if (!running.value) return@post
            startScan()
            main.removeCallbacks(stopScanR)
            main.postDelayed(stopScanR, SCAN_MS)
        }
    }

    // ---------------- 发送 ----------------
    /** 发新消息：dst=0 群发；否则单发给该短 ID。返回消息，false 表示没有开启。 */
    fun send(ctx: Context, text: String, dst: Int, withLocation: Boolean): CommProto.Msg? {
        val t = CommProto.truncUtf8(text.trim(), CommProto.TEXT_MAX)
        if (t.isEmpty()) return null
        app = ctx.applicationContext
        CommStore.load(app)
        val fix = if (withLocation) MyFix.last.value?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L } else null
        val m = CommProto.Msg(
            java.util.Random().nextInt(), nodeId(app), dst, CommProto.DEFAULT_TTL, System.currentTimeMillis() / 1000,
            nick(app), t, fix?.latitude, fix?.longitude,
        )
        CommStore.add(CommStore.Stored(m, mine = true, visible = true, status = CommStore.ST_PENDING, delivered = 0, recvTime = System.currentTimeMillis(), rssi = 0))
        val ns = synchronized(neighborMap) { neighborMap.values.toList() }
        ns.forEach { backfill(it) }
        if (running.value && ns.isEmpty()) scanNow()
        return m
    }

    /** 把还没送给该邻居的消息排进队列（新发的、收到的、接力的都走这里）。 */
    private fun backfill(n: Neighbor) {
        if (!running.value) return
        val me = nodeId(app)
        val nowS = System.currentTimeMillis() / 1000
        val nowMs = System.currentTimeMillis()
        for (s in CommStore.list.value.takeLast(40)) {
            val m = s.msg
            if (nowS - m.time > BACKFILL_WINDOW_S || m.src == n.id) continue
            val sendTtl = if (s.mine) m.ttl else m.ttl - 1
            if (sendTtl < 1) continue
            if (!s.mine && m.dst == me) continue // 发给我的单发，到我为止
            val k = m.key + "@" + n.id
            if (k in delivered || k in queued) continue
            if (nowMs - (failedAt[k] ?: 0L) < RETRY_MS) continue
            queued.add(k)
            queue.put(Job(n.id, m.withTtl(sendTtl), k, s.mine))
        }
    }

    private fun startWorker() {
        if (worker?.isAlive == true) return
        worker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val job = queue.take()
                    runJob(job)
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
                // 保险：发送线程不能带着未捕获异常把整个进程拖垮；退出后下次 bringUp 会重建
            }
        }, "comm-sender").apply { isDaemon = true; start() }
    }

    private fun runJob(job: Job) {
        val n = synchronized(neighborMap) { neighborMap[job.neighborId] }
        var ok = false
        var fresh = false // 对方第一次收到才计入“已送达 N 台”；重复（如重启后补发）只算送达不再加数
        if (n != null) {
            val st = runCatching { Gatt.send(app, n.device, CommProto.encode(job.msg), job.msg.id, nextXfer()) }.getOrDefault(-1)
            // 只有对方明确回“新消息/重复”才算送达；ST_BAD 与 99（写入完成但没等到确认）不算
            ok = st == CommProto.ST_NEW || st == CommProto.ST_DUP
            fresh = st == CommProto.ST_NEW
            if (st == 99) {
                // 写入都成功但没收到确认：最多再试 2 次，仍无确认就当已送达，避免永远重发
                val c = (unconfirmed[job.key] ?: 0) + 1
                unconfirmed[job.key] = c
                if (c >= 3) ok = true
            }
        }
        queued.remove(job.key)
        if (ok) {
            delivered.add(job.key)
            if (job.mine) {
                val cur = CommStore.list.value.firstOrNull { it.msg.key == job.msg.key }
                // 确认丢失后重试得到“重复”时，当前计数还是 0，也要记 1 台
                CommStore.update(job.msg.key, CommStore.ST_SENT, if (fresh || (cur?.delivered ?: 0) == 0) 1 else 0)
            }
        } else {
            failedAt[job.key] = System.currentTimeMillis()
            if (job.mine) { val cur = CommStore.list.value.firstOrNull { it.msg.key == job.msg.key }; if (cur != null && cur.delivered == 0) CommStore.update(job.msg.key, CommStore.ST_FAILED) }
        }
    }

    private fun nextXfer(): Int { xfer = (xfer + 1) and 255; return xfer }

    /** GATT 客户端：连接 → 协商 MTU → 发现服务 → 订阅 ACK → 分片写 → 等 ack → 断开。 */
    private object Gatt {
        private class Ev(val t: Int, val status: Int, val value: ByteArray? = null)
        private const val CONN = 0; private const val MTU = 1; private const val SVC = 2; private const val DESC = 3; private const val WRITE = 4; private const val NOTI = 5

        /** 返回 ack 状态（0/1/2），99 = 全部写入成功但没等到 ack，-1 = 失败。失败时用 20 字节分片重试一次。 */
        fun send(ctx: Context, dev: BluetoothDevice, data: ByteArray, id: Int, xfer: Int): Int {
            for (attempt in 0..1) {
                val r = once(ctx, dev, data, id, xfer, requestMtu = attempt == 0)
                if (r >= 0) return r
                if (Thread.currentThread().isInterrupted) return -1
                try { Thread.sleep(400) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return -1 }
            }
            return -1
        }

        private fun once(ctx: Context, dev: BluetoothDevice, data: ByteArray, id: Int, xfer: Int, requestMtu: Boolean): Int {
            val q = LinkedBlockingQueue<Ev>()
            val stash = ArrayList<Ev>() // 等别的事件时提前到的 ACK 通知先存着，别丢
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { q.put(Ev(CONN, if (status == 0) newState else -1)) }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { q.put(Ev(MTU, if (status == 0) mtu else 23)) }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { q.put(Ev(SVC, status)) }
                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { q.put(Ev(DESC, status)) }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { q.put(Ev(WRITE, status)) }
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { q.put(Ev(NOTI, 0, value)) }
                @Deprecated("pre-33") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { q.put(Ev(NOTI, 0, c.value)) }
            }
            var gatt: BluetoothGatt? = null
            try {
                gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                val c = await(q, CONN, 12_000) ?: return -1
                if (c.status != BluetoothProfile.STATE_CONNECTED) return -1
                var mtu = 23
                if (requestMtu && gatt.requestMtu(247)) mtu = await(q, MTU, 2_500)?.status ?: 23
                if (!gatt.discoverServices()) return -1
                if ((await(q, SVC, 10_000)?.status ?: -1) != 0) return -1
                val svc = gatt.getService(CommProto.SERVICE) ?: return -1
                val rx = svc.getCharacteristic(CommProto.RX) ?: return -1
                val ack = svc.getCharacteristic(CommProto.ACK)
                var canAck = false
                val cccd = ack?.getDescriptor(CommProto.CCCD)
                if (ack != null && cccd != null && gatt.setCharacteristicNotification(ack, true)) {
                    val wrote = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
                    else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(cccd) }
                    canAck = wrote && (await(q, DESC, 4_000)?.status ?: -1) == 0
                }
                val chunk = (mtu - 3 - CommProto.FRAG_HEAD).coerceAtLeast(CommProto.DEFAULT_CHUNK).coerceAtMost(200)
                for (frag in CommProto.fragment(data, xfer, chunk)) {
                    val started = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(rx, frag, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                    else { rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; rx.value = frag; gatt.writeCharacteristic(rx) }
                    if (!started) return -1
                    if ((await(q, WRITE, 6_000, stash)?.status ?: -1) != 0) return -1
                }
                if (!canAck) return 99
                val deadline = System.currentTimeMillis() + 4_000
                while (true) {
                    val e = if (stash.isNotEmpty()) stash.removeAt(0) else {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) return 99
                        await(q, NOTI, left) ?: return 99
                    }
                    val v = e.value ?: continue
                    if (v.size >= 5 && ((v[0].toInt() and 255) shl 24 or ((v[1].toInt() and 255) shl 16) or ((v[2].toInt() and 255) shl 8) or (v[3].toInt() and 255)) == id) return v[4].toInt()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt() // 恢复中断标志，让外层 worker 真正退出
                return -1
            } catch (_: SecurityException) {
                return -1
            } finally {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
            }
        }

        /** 等指定类型的事件；中途收到断开事件就当失败。 */
        private fun await(q: LinkedBlockingQueue<Ev>, type: Int, ms: Long, stash: MutableList<Ev>? = null): Ev? {
            val end = System.currentTimeMillis() + ms
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return null
                val e = q.poll(left, TimeUnit.MILLISECONDS) ?: return null
                if (e.t == type) return e
                if (e.t == NOTI && stash != null) stash.add(e)
                if (e.t == CONN && e.status != BluetoothProfile.STATE_CONNECTED && type != CONN) return null
            }
        }
    }

    // ---------------- GATT 服务端（收消息） ----------------
    private fun openServer(mgr: BluetoothManager?) {
        runCatching { server?.close() }
        val s = mgr?.openGattServer(app, serverCb) ?: run { error.value = "无法打开蓝牙 GATT 服务：收不到别人的消息"; return }
        val svc = BluetoothGattService(CommProto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(CommProto.RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        val ack = BluetoothGattCharacteristic(CommProto.ACK, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        ack.addDescriptor(BluetoothGattDescriptor(CommProto.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(ack)
        ackChar = ack
        s.addService(svc)
        server = s
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED) { subscribed.remove(device.address); reassemblers.remove(device.address) }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor, prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (d.uuid == CommProto.CCCD) subscribed[device.address] = value != null && value.isNotEmpty() && value[0].toInt() != 0
            if (responseNeeded) server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic, prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value)
            if (c.uuid != CommProto.RX || value == null) return
            val full = reassemblers.getOrPut(device.address) { CommProto.Reassembler() }.add(value) ?: return
            val m = CommProto.decode(full)
            if (m == null) { notifyAck(device, 0, CommProto.ST_BAD); return }
            notifyAck(device, m.id, onIncoming(m))
        }
    }

    private fun notifyAck(dev: BluetoothDevice, id: Int, status: Int) {
        val s = server ?: return
        val c = ackChar ?: return
        if (subscribed[dev.address] != true) return
        val v = CommProto.ack(id, status)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(dev, c, false, v)
            else { c.value = v; s.notifyCharacteristicChanged(dev, c, false) }
        }
    }

    /** 收到一条完整消息：去重、入库、需要的话接力。返回 ack 状态。 */
    private fun onIncoming(m: CommProto.Msg): Int {
        val me = nodeId(app)
        if (m.src == me || CommStore.has(m.key)) return CommProto.ST_DUP
        val visible = m.broadcast || m.dst == me
        val rssi = synchronized(neighborMap) { neighborMap[m.src]?.rssi ?: 0 }
        val isNew = CommStore.add(CommStore.Stored(m, mine = false, visible = visible, status = CommStore.ST_SENT, delivered = 0, recvTime = System.currentTimeMillis(), rssi = rssi))
        if (!isNew) return CommProto.ST_DUP
        CommStore.flush() // 落盘后再回 ACK：否则进程此刻被杀，发送方以为送达、不再重试，消息就丢了
        if (visible) incoming.value = incoming.value + 1
        // 接力：群发消息，或发给别人的单发消息，TTL>1 就转发给其他邻居
        if (m.ttl > 1 && (m.broadcast || m.dst != me)) {
            val ns = synchronized(neighborMap) { neighborMap.values.toList() }
            ns.forEach { backfill(it) }
        }
        return CommProto.ST_NEW
    }
}
