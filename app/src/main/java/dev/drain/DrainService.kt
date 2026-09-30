package dev.drain

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.location.*
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object State {
    @Volatile var running = false
    val primes = AtomicLong()
    @Volatile var lastPrime = 0L
    @Volatile var lat = Double.NaN
    @Volatile var lon = Double.NaN
    @Volatile var acc = 0f
    @Volatile var wifi = 0
    @Volatile var bt = 0
    @Volatile var cells = 0
}

@SuppressLint("MissingPermission")
class DrainService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val bg = HandlerThread("drain").apply { start() }
    private val bgH by lazy { Handler(bg.looper) }
    private val btSeen = ConcurrentHashMap.newKeySet<String>()
    private var wake: PowerManager.WakeLock? = null
    private var cam: CameraDevice? = null
    private var sess: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var rec: AudioRecord? = null
    private var locL: LocationListener? = null
    private var wifiRx: BroadcastReceiver? = null
    private var btRx: BroadcastReceiver? = null
    private var leCb: ScanCallback? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (State.running) return START_NOT_STICKY
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("d", "Drain test", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "d")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Drainer running")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        State.primes.set(0); State.lastPrime = 0; State.lat = Double.NaN; State.lon = Double.NaN; State.wifi = 0; State.bt = 0; State.cells = 0
        btSeen.clear()
        State.running = true
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drain:cpu").apply { acquire() }
        listOf(::startCpu, ::startVibe, ::startCamera, ::startMic, ::startLoc, ::startWifi, ::startBt, ::startCell).forEach { runCatching(it) }
        return START_NOT_STICKY
    }

    private fun startCpu() {
        val cores = Runtime.getRuntime().availableProcessors()
        repeat(cores) { t ->
            thread(isDaemon = true) {
                var n = 3L + 2L * t
                while (State.running) {
                    if (isPrime(n)) { State.primes.incrementAndGet(); State.lastPrime = n }
                    n += 2L * cores
                }
            }
        }
    }

    private fun isPrime(n: Long): Boolean {
        var d = 3L
        while (d * d <= n) { if (n % d == 0L) return false; d += 2 }
        return true
    }

    private fun startVibe() {
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 1000), intArrayOf(0, 255), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }

    // Back camera streams into a discarded ImageReader; torch is set in the repeating request.
    private fun startCamera() {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val size = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 1920 }.maxBy { it.width * it.height }
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, bgH)
        reader = r
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                cam = d
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(r.surface)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }.build()
                d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { sess = s; s.setRepeatingRequest(req, null, bgH) }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bgH)
            }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, e: Int) { d.close() }
        }, bgH)
    }

    private fun startMic() {
        val rate = 44100
        val buf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
        r.startRecording()
        rec = r
        thread(isDaemon = true) {
            val b = ShortArray(buf / 2)
            runCatching { while (State.running) r.read(b, 0, b.size) }
        }
    }

    private fun startLoc() {
        val lm = getSystemService(LocationManager::class.java)
        val l = LocationListener { State.lat = it.latitude; State.lon = it.longitude; State.acc = it.accuracy }
        locL = l
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER))
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()) }
    }

    private fun startWifi() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { State.wifi = wm.scanResults.size; wm.startScan() }
        }
        wifiRx = rx
        ContextCompat.registerReceiver(this, rx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        main.post(object : Runnable {
            override fun run() { if (State.running) { wm.startScan(); main.postDelayed(this, 1000) } }
        })
    }

    private fun startBt() {
        val ad = getSystemService(BluetoothManager::class.java).adapter ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) { btSeen.add(r.device.address); State.bt = btSeen.size }
        }
        leCb = cb
        ad.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == BluetoothDevice.ACTION_FOUND) {
                    i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { btSeen.add(it.address); State.bt = btSeen.size }
                } else if (State.running) ad.startDiscovery()
            }
        }
        btRx = rx
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        ad.startDiscovery()
    }

    // Back-to-back cell info refresh requests (100 ms gap); a forced network scan is system-app only.
    private fun startCell() {
        val tm = getSystemService(TelephonyManager::class.java)
        val ex = Executor { bgH.post(it) }
        val cb = object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: MutableList<CellInfo>) { State.cells = cellInfo.size; next() }
            override fun onError(errorCode: Int, detail: Throwable?) { next() }
            fun next() { if (State.running) bgH.postDelayed({ runCatching { tm.requestCellInfoUpdate(ex, this) } }, 100) }
        }
        tm.requestCellInfoUpdate(ex, cb)
    }

    override fun onDestroy() {
        State.running = false
        main.removeCallbacksAndMessages(null)
        runCatching { getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
        runCatching { sess?.close() }
        runCatching { cam?.close() }
        runCatching { reader?.close() }
        runCatching { rec?.stop(); rec?.release() }
        runCatching { locL?.let { getSystemService(LocationManager::class.java).removeUpdates(it) } }
        runCatching { wifiRx?.let { unregisterReceiver(it) } }
        runCatching { btRx?.let { unregisterReceiver(it) } }
        runCatching {
            val ad = getSystemService(BluetoothManager::class.java).adapter
            leCb?.let { ad.bluetoothLeScanner?.stopScan(it) }
            ad.cancelDiscovery()
        }
        runCatching { wake?.release() }
        bg.quitSafely()
        super.onDestroy()
    }
}
