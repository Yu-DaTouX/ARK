package app.ark.sensors

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ark.MainActivity

/**
 * 蓝牙求救信标（前台服务）：连续低功耗广播 + 每分钟扫 10 秒。包格式见 [Beacon]。
 * 定位：每 60 秒一次（GPS + 网络，有哪个用哪个）；没有定位时仍广播状态和短消息，位置标“未知”。
 */
@SuppressLint("MissingPermission")
class BeaconService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var adv: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var lm: LocationManager? = null
    private var locL: SimpleLocationListener? = null
    private var scanning = false
    private var started = false

    override fun onBind(i: Intent?): IBinder? = null

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Beacon.error.value = "广播失败（错误 $errorCode），可能是手机不支持蓝牙广播" }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val p = Beacon.decode(r.scanRecord?.getManufacturerSpecificData(Beacon.COMPANY)) ?: return
            if (p.id == Beacon.nodeId(this@BeaconService)) return
            Beacon.onSeen(Beacon.Seen(p.id, p.sos, p.lat, p.lon, p.ageMin, p.msg, r.rssi, System.currentTimeMillis()))
        }
        override fun onScanFailed(errorCode: Int) { Beacon.error.value = "扫描失败（错误 $errorCode）" }
    }

    private val cycle = object : Runnable {
        override fun run() {
            advertise()
            startScan()
            h.postDelayed({ stopScan() }, Beacon.SCAN_MS)
            h.postDelayed(this, Beacon.PERIOD_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        Beacon.loadConfig(this)
        TrackService.channel(this)
        // 没有定位权限时不声明 location 类型（Android 14 起会检查前提条件），信标仍可广播（位置标“未知”）
        val hasLoc = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) else 0
        try {
            ServiceCompat.startForeground(this, ID, notif(), type)
        } catch (e: Exception) {
            Beacon.error.value = "信标无法在后台启动：${e.message}"; stopSelf(); return START_NOT_STICKY
        }
        if (app.ark.comm.CommEngine.missingPermissions(this).isNotEmpty()) { Beacon.error.value = "缺少蓝牙权限"; stopSelf(); return START_NOT_STICKY }
        if (!acquireBt()) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            started = true
            ContextCompat.registerReceiver(this, btReceiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
            Beacon.running.value = true
            startLocation()
            h.post(cycle)
        } else {
            advertise() // 用户改了状态 / 消息：马上重发
        }
        return START_STICKY
    }

    private var receiverOn = false

    /** 取得适配器广播/扫描句柄；蓝牙没开返回 false。 */
    private fun acquireBt(): Boolean {
        val bt = try { getSystemService(BluetoothManager::class.java)?.adapter } catch (_: SecurityException) { null }
        val on = try { bt?.isEnabled == true } catch (_: SecurityException) { false }
        if (bt == null || !on) { Beacon.error.value = "蓝牙没有打开"; return false }
        return try {
            adv = bt.bluetoothLeAdvertiser
            scanner = bt.bluetoothLeScanner
            if (adv == null) Beacon.error.value = "这台手机不支持蓝牙广播，只能接收别人的信标"
            else Beacon.error.value = null
            true
        } catch (e: SecurityException) { Beacon.error.value = "缺少蓝牙权限"; false }
    }

    private val btReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    stopScan(); runCatching { adv?.stopAdvertising(advCb) }
                    adv = null; scanner = null
                    Beacon.error.value = "蓝牙被关闭了：打开后会自动恢复信标"
                }
                android.bluetooth.BluetoothAdapter.STATE_ON -> if (app.ark.comm.CommEngine.missingPermissions(this@BeaconService).isEmpty() && acquireBt()) advertise()
            }
        }
    }

    private fun startLocation() {
        val m = getSystemService(LocationManager::class.java); lm = m
        val l = SimpleLocationListener { MyFix.offer(it) }
        locL = l
        MyFix.lastKnown(this)?.let { MyFix.offer(it) }
        runCatching {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 0f, l, Looper.getMainLooper())
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 0f, l, Looper.getMainLooper())
        }
    }

    private fun advertise() {
        val a = adv ?: return
        runCatching { a.stopAdvertising(advCb) }
        val fix = MyFix.last.value
        val age = fix?.let { ((System.currentTimeMillis() - it.time) / 60_000L).toInt() } ?: 0
        val pkt = Beacon.Packet(Beacon.nodeId(this), Beacon.sos, fix?.latitude, fix?.longitude, age, Beacon.message)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addManufacturerData(Beacon.COMPANY, Beacon.encode(pkt)).build()
        runCatching { a.startAdvertising(settings, data, advCb) }.onFailure { Beacon.error.value = "广播失败：${it.message}" }
        refreshNotif()
    }

    private var shown = ""
    /** 通知内容（状态、发现数）变了才重发：原来每分钟无条件 notify 两次。 */
    private fun refreshNotif() {
        val k = "${Beacon.sos}|${Beacon.seen.value.size}"
        if (k == shown || destroyed) return // onDestroy 里的 stopScan 不要再发通知，以免留下关不掉的残留通知
        shown = k
        getSystemService(NotificationManager::class.java).notify(ID, notif())
    }

    private fun startScan() {
        val s = scanner ?: return
        if (scanning) return
        val filter = ScanFilter.Builder().setManufacturerData(Beacon.COMPANY, byteArrayOf(Beacon.MAGIC0.toByte(), Beacon.MAGIC1.toByte()), byteArrayOf(-1, -1)).build()
        val st = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { s.startScan(listOf(filter), st, scanCb); scanning = true }.onFailure { Beacon.error.value = "扫描失败：${it.message}" }
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { scanner?.stopScan(scanCb) }
        scanning = false
        refreshNotif()
    }

    private var destroyed = false

    override fun onDestroy() {
        destroyed = true
        if (receiverOn) { runCatching { unregisterReceiver(btReceiver) }; receiverOn = false }
        h.removeCallbacksAndMessages(null)
        stopScan()
        runCatching { adv?.stopAdvertising(advCb) }
        locL?.let { runCatching { lm?.removeUpdates(it) } }
        Beacon.running.value = false
        super.onDestroy()
    }

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, BeaconService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, TrackService.CH)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("方舟：蓝牙信标已开启（${if (Beacon.sos) "求救" else "平安"}）")
            .setContentText("附近已发现 ${Beacon.seen.value.size} 个；每分钟扫描 10 秒")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭", stop).build())
            .build()
    }

    companion object {
        const val ID = 42
        const val ACTION_STOP = "app.ark.BEACON_STOP"
        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, BeaconService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BeaconService::class.java)) }
    }
}
