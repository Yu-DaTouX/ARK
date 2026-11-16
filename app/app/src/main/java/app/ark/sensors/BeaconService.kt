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
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, ID, notif(), type)
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) { Beacon.error.value = "蓝牙没有打开"; stopSelf(); return START_NOT_STICKY }
        adv = bt.bluetoothLeAdvertiser
        scanner = bt.bluetoothLeScanner
        if (adv == null) Beacon.error.value = "这台手机不支持蓝牙广播，只能接收别人的信标"
        else Beacon.error.value = null
        if (!started) {
            started = true
            Beacon.running.value = true
            startLocation()
            h.post(cycle)
        } else {
            advertise() // 用户改了状态 / 消息：马上重发
        }
        return START_STICKY
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
        getSystemService(NotificationManager::class.java).notify(ID, notif())
    }

    private fun startScan() {
        val s = scanner ?: return
        if (scanning) return
        val filter = ScanFilter.Builder().setManufacturerData(Beacon.COMPANY, byteArrayOf(Beacon.MAGIC0.toByte(), Beacon.MAGIC1.toByte()), byteArrayOf(-1, -1)).build()
        val st = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { s.startScan(listOf(filter), st, scanCb); scanning = true }
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { scanner?.stopScan(scanCb) }
        scanning = false
        getSystemService(NotificationManager::class.java).notify(ID, notif())
    }

    override fun onDestroy() {
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
