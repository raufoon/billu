package com.billu.printer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class BilluBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "billu_bridge_channel"
        const val NOTIF_ID = 4201
        const val ACTION_PRINT_FROM_NOTIF = "com.billu.printer.ACTION_PRINT_FROM_NOTIF"
        const val ACTION_TOGGLE_BUBBLE = "com.billu.printer.ACTION_TOGGLE_BUBBLE"

        var instance: BilluBridgeService? = null
        var sharedWebView: WebView? = null
        private var webContextWrapper: MutableContextWrapper? = null
        var isWebViewLoaded = false
        val pendingJsCalls = mutableListOf<String>()

        fun getOrCreateWebView(context: Context): WebView {
            val existing = sharedWebView
            if (existing != null) {
                webContextWrapper?.baseContext = context
                return existing
            }
            val wrapper = MutableContextWrapper(context)
            webContextWrapper = wrapper
            val wv = WebView(wrapper).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = WebSettings.LOAD_DEFAULT

                addJavascriptInterface(NativeJsBridge(), "BilluNative")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isWebViewLoaded = true
                        instance?.pushBleStateToJs()
                        synchronized(pendingJsCalls) {
                            for (js in pendingJsCalls) {
                                view?.evaluateJavascript(js, null)
                            }
                            pendingJsCalls.clear()
                        }
                    }
                }
                loadUrl("file:///android_asset/index.html")
            }
            sharedWebView = wv
            return wv
        }

        fun restoreServiceContext() {
            val svc = instance ?: return
            webContextWrapper?.baseContext = svc
        }

        fun evalJsOnMain(script: String) {
            Handler(Looper.getMainLooper()).post {
                val wv = sharedWebView
                if (wv != null && isWebViewLoaded) {
                    wv.evaluateJavascript(script, null)
                } else {
                    synchronized(pendingJsCalls) {
                        pendingJsCalls.add(script)
                    }
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var activeGatt: BluetoothGatt? = null
    @Volatile var writeChar: BluetoothGattCharacteristic? = null
    @Volatile var isBleConnecting = false
    private var writeLatch: CountDownLatch? = null

    private var windowManager: WindowManager? = null
    private var floatingBubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isBubbleAdded = false
    @Volatile var isPartnerAppInForeground = false

    private val bgReconnectRunnable = object : Runnable {
        override fun run() {
            if (writeChar == null && !isBleConnecting) {
                val savedMac = getSavedPrinterMac()
                if (savedMac.isNotEmpty()) {
                    attemptSilentMacConnect(savedMac)
                }
            }
            mainHandler.postDelayed(this, 3200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundSafely("🟡 Billu Ready — Looking for printer...")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Billu::PrinterWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btMgr?.adapter

        getOrCreateWebView(this)
        setupFloatingBubble()
        mainHandler.post(bgReconnectRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PRINT_FROM_NOTIF -> {
                triggerOrderScrapeFromService()
            }
            ACTION_TOGGLE_BUBBLE -> {
                val prefs = getSharedPreferences("billu_native_prefs", MODE_PRIVATE)
                val cur = prefs.getBoolean("bubble_enabled", true)
                prefs.edit().putBoolean("bubble_enabled", !cur).apply()
                updateFloatingBubbleVisibility(isPartnerAppInForeground)
                updateForegroundNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(bgReconnectRunnable)
        removeFloatingBubble()
        try { activeGatt?.close() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Billu Printer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth printer connected and provides instant Order Print"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val printIntent = Intent(this, BilluBridgeService::class.java).apply {
            action = ACTION_PRINT_FROM_NOTIF
        }
        val printPending = PendingIntent.getService(
            this, 1, printIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleBubbleIntent = Intent(this, BilluBridgeService::class.java).apply {
            action = ACTION_TOGGLE_BUBBLE
        }
        val togglePending = PendingIntent.getService(
            this, 2, toggleBubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prefs = getSharedPreferences("billu_native_prefs", MODE_PRIVATE)
        val bubbleOn = prefs.getBoolean("bubble_enabled", true)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Billu Thermal Bridge")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "🐱 Print Order", printPending)
            .addAction(0, if (bubbleOn) "Hide 🐱 Button" else "Show 🐱 Button", togglePending)
            .build()
    }

    private fun startForegroundSafely(statusText: String) {
        val notif = buildNotification(statusText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            try { startForeground(NOTIF_ID, notif) } catch (_: Exception) {}
        }
    }

    fun updateForegroundNotification(customStatus: String? = null) {
        val name = getSavedPrinterName().ifEmpty { "Printer" }
        val status = customStatus ?: if (writeChar != null) {
            "🟢 Connected: $name • Ready to Print"
        } else if (getSavedPrinterMac().isNotEmpty()) {
            "🟡 Standby ($name Off) — Auto-connects when turned ON"
        } else {
            "⚠️ No printer paired — Open Billu to pair"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val sizePx = (56 * dp).toInt()

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D70F64"))
            setStroke((2 * dp).toInt(), Color.WHITE)
        }

        val bubble = TextView(this).apply {
            text = "🐱"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            gravity = Gravity.CENTER
            background = bgDrawable
            elevation = 12 * dp
            visibility = View.GONE
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - sizePx - (12 * dp).toInt())
            y = (resources.displayMetrics.heightPixels / 3)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var downTime = 0L

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    if (isBubbleAdded) {
                        try { windowManager?.updateViewLayout(bubble, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - touchX)
                    val dy = abs(event.rawY - touchY)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (dx < 18 && dy < 18) {
                        if (elapsed > 650) {
                            bringBilluActivityToFront()
                        } else {
                            triggerOrderScrapeFromService()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        floatingBubble = bubble
        bubbleParams = params
        updateFloatingBubbleVisibility(false)
    }

    fun updateFloatingBubbleVisibility(partnerAppActive: Boolean) {
        isPartnerAppInForeground = partnerAppActive
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                return@post
            }
            val prefs = getSharedPreferences("billu_native_prefs", MODE_PRIVATE)
            val enabledByUser = prefs.getBoolean("bubble_enabled", true)
            val bubble = floatingBubble ?: return@post
            val params = bubbleParams ?: return@post

            if (enabledByUser && partnerAppActive) {
                if (!isBubbleAdded) {
                    try {
                        windowManager?.addView(bubble, params)
                        isBubbleAdded = true
                    } catch (_: Exception) {}
                }
                bubble.visibility = View.VISIBLE
            } else {
                if (isBubbleAdded) {
                    bubble.visibility = View.GONE
                }
            }
        }
    }

    private fun removeFloatingBubble() {
        if (isBubbleAdded && floatingBubble != null) {
            try { windowManager?.removeView(floatingBubble) } catch (_: Exception) {}
            isBubbleAdded = false
        }
    }

    fun triggerOrderScrapeFromService() {
        val acc = BilluAccessibilityService.instance
        if (acc == null) {
            Toast.makeText(
                this,
                "⚠️ Enable 'Billu Order Reader' once in Android Accessibility Settings",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        // Keep bubble View.VISIBLE (showing ⏳) so Android 14/15/16 preserves our visible-overlay exemption to launch Billu!
        mainHandler.post {
            floatingBubble?.text = "⏳"
            floatingBubble?.visibility = View.VISIBLE
        }

        mainHandler.postDelayed({
            acc.captureFoodpandaOrderMultiScroll { b64 ->
                mainHandler.post {
                    floatingBubble?.text = "🐱"
                }
                if (b64.isNullOrEmpty()) {
                    Toast.makeText(this, "⚠️ No order text found on screen", Toast.LENGTH_SHORT).show()
                } else {
                    val prefs = getSharedPreferences("billu_native_prefs", MODE_PRIVATE)
                    val silentBg = prefs.getBoolean("js_silent_bg_enabled", false)
                    val printerReady = (writeChar != null)
                    val shouldOpenBilluUi = (!silentBg) || (!printerReady)
                    deliverCapturedOrderToJs(b64, bringToFront = shouldOpenBilluUi)
                }
            }
        }, 80)
    }

    fun deliverCapturedOrderToJs(b64: String, bringToFront: Boolean) {
        val safeB64 = b64.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
        if (bringToFront) {
            bringBilluActivityToFront()
        }
        evalJsOnMain("if(window.onNativeOrderCaptured){window.onNativeOrderCaptured(\"$safeB64\");}else if(window.onBilluBgOrder){window.onBilluBgOrder(\"$safeB64\");}")
    }

    fun bringBilluActivityToFront() {
        mainHandler.post {
            // CRITICAL: Do NOT hide floatingBubble before startActivity(), because Android 14+ requires
            // a currently visible SYSTEM_ALERT_WINDOW overlay to allow background activity launches!
            // MainActivity.onResume() -> notifyUiVisibility(true) will hide the bubble once Billu opens.
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            val acc = BilluAccessibilityService.instance
            try {
                if (acc != null) {
                    acc.startActivity(intent)
                } else {
                    startActivity(intent)
                }
            } catch (_: Exception) {
                try { startActivity(intent) } catch (_: Exception) {}
            }
        }
    }

    fun notifyUiVisibility(visible: Boolean) {
        if (visible) {
            updateFloatingBubbleVisibility(false)
        }
        evalJsOnMain("window.isNativeUiVisible = $visible;")
    }

    fun getSavedPrinterMac(): String {
        val prefs = getSharedPreferences("billu_native_prefs", MODE_PRIVATE)
        return prefs.getString("saved_printer_mac", "") ?: ""
    }

    fun getSavedPrinterName(): String {
        val prefs = getSharedPreferences("billu_native_prefs", MODE_PRIVATE)
        return prefs.getString("saved_printer_name", "") ?: ""
    }

    fun savePrinterMacAndName(mac: String, name: String) {
        getSharedPreferences("billu_native_prefs", MODE_PRIVATE).edit()
            .putString("saved_printer_mac", mac)
            .putString("saved_printer_name", name)
            .apply()
    }

    fun triggerImmediateBleReconnect() {
        val mac = getSavedPrinterMac()
        if (mac.isNotEmpty() && writeChar == null && !isBleConnecting) {
            attemptSilentMacConnect(mac)
        }
    }

    fun attemptSilentMacConnect(mac: String) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || isBleConnecting || writeChar != null) return

        try {
            val device = adapter.getRemoteDevice(mac)
            connectToBluetoothDevice(device)
        } catch (_: Exception) {}
    }

    private fun connectToBluetoothDevice(device: BluetoothDevice) {
        if (isBleConnecting || writeChar != null) return
        isBleConnecting = true

        try { activeGatt?.close() } catch (_: Exception) {}

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mainHandler.postDelayed({
                        try { gatt.discoverServices() } catch (_: Exception) {}
                    }, 150)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    writeChar = null
                    isBleConnecting = false
                    try { gatt.close() } catch (_: Exception) {}
                    if (activeGatt === gatt) activeGatt = null
                    updateForegroundNotification()
                    pushBleStateToJs()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    isBleConnecting = false
                    try { gatt.disconnect() } catch (_: Exception) {}
                    return
                }
                var found: BluetoothGattCharacteristic? = null
                for (service in gatt.services) {
                    for (c in service.characteristics) {
                        val uuidStr = c.uuid.toString().lowercase()
                        val props = c.properties
                        val writable = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ||
                                (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        if (uuidStr.contains("ae01") || uuidStr.contains("ff02") || writable) {
                            found = c
                            if (uuidStr.contains("ae01")) break
                        }
                    }
                    if (found != null && found.uuid.toString().lowercase().contains("ae01")) break
                }

                if (found != null) {
                    writeChar = found
                    isBleConnecting = false
                    val devName = try { gatt.device.name ?: "Thermal Printer" } catch (_: Exception) { "Thermal Printer" }
                    savePrinterMacAndName(gatt.device.address, devName)
                    updateForegroundNotification()
                    pushBleStateToJs()
                } else {
                    isBleConnecting = false
                    try { gatt.disconnect() } catch (_: Exception) {}
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                writeLatch?.countDown()
            }
        }

        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }

        mainHandler.postDelayed({
            if (isBleConnecting && writeChar == null) {
                isBleConnecting = false
                try { activeGatt?.close() } catch (_: Exception) {}
                activeGatt = null
            }
        }, 4200)
    }

    fun writeBlePacketSync(bytes: ByteArray): Boolean {
        val gatt = activeGatt ?: return false
        val char = writeChar ?: return false
        return try {
            val noResp = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            char.writeType = if (noResp) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            @Suppress("DEPRECATION")
            char.value = bytes
            val latch = CountDownLatch(1)
            writeLatch = latch
            @Suppress("DEPRECATION")
            val started = gatt.writeCharacteristic(char)
            if (!started) {
                Thread.sleep(15)
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            latch.await(180, TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pushBleStateToJs() {
        val connected = (writeChar != null)
        val mac = getSavedPrinterMac()
        val name = getSavedPrinterName().replace("\"", "\\\"")
        evalJsOnMain("if(window.onNativeBleState){window.onNativeBleState($connected, \"$mac\", \"$name\");}")
    }

    fun showNativeBlePrinterPicker() {
        val act = MainActivity.currentInstance ?: return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(act, "⚠️ Turn ON Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val discovered = LinkedHashMap<String, BluetoothDevice>()
        try {
            for (b in adapter.bondedDevices) {
                discovered[b.address] = b
            }
        } catch (_: Exception) {}

        val scanner = adapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                if (!dev.address.isNullOrEmpty()) {
                    discovered[dev.address] = dev
                }
            }
        }

        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner?.startScan(null, settings, scanCallback)
        } catch (_: Exception) {}

        Toast.makeText(act, "🔍 Scanning for Bluetooth printers (2.5s)...", Toast.LENGTH_SHORT).show()

        mainHandler.postDelayed({
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
            if (discovered.isEmpty()) {
                Toast.makeText(act, "⚠️ No Bluetooth devices found — make sure printer is ON", Toast.LENGTH_LONG).show()
                return@postDelayed
            }
            val list = discovered.values.toList()
            val labels = list.map { d ->
                val n = try { d.name } catch (_: Exception) { null }
                "${if (n.isNullOrBlank()) "Unknown Device" else n} [${d.address}]"
            }.toTypedArray()

            AlertDialog.Builder(act)
                .setTitle("Select Thermal Printer")
                .setItems(labels) { _, which ->
                    val chosen = list[which]
                    val chosenName = try { chosen.name ?: "Thermal Printer" } catch (_: Exception) { "Thermal Printer" }
                    savePrinterMacAndName(chosen.address, chosenName)
                    isBleConnecting = false
                    writeChar = null
                    connectToBluetoothDevice(chosen)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, 2500)
    }

    class NativeJsBridge {
        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun isConnected(): Boolean = instance?.writeChar != null

        @JavascriptInterface
        fun getSavedMac(): String = instance?.getSavedPrinterMac() ?: ""

        @JavascriptInterface
        fun getSavedName(): String = instance?.getSavedPrinterName() ?: ""

        @JavascriptInterface
        fun syncSilentModeState(isSilentActive: Boolean, customPartnerPkg: String) {
            val svc = instance ?: return
            svc.getSharedPreferences("billu_native_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("js_silent_bg_enabled", isSilentActive)
                .putString("custom_partner_pkg", customPartnerPkg.trim())
                .apply()
        }

        @JavascriptInterface
        fun scanAndPickPrinter() {
            Handler(Looper.getMainLooper()).post {
                instance?.showNativeBlePrinterPicker()
            }
        }

        @JavascriptInterface
        fun connectToMac(mac: String): Boolean {
            val svc = instance ?: return false
            if (mac.isNotBlank()) {
                svc.savePrinterMacAndName(mac, svc.getSavedPrinterName().ifEmpty { "Thermal Printer" })
            }
            svc.triggerImmediateBleReconnect()
            return svc.writeChar != null
        }

        @JavascriptInterface
        fun forgetPrinter() {
            val svc = instance ?: return
            svc.savePrinterMacAndName("", "")
            svc.writeChar = null
            svc.isBleConnecting = false
            try { svc.activeGatt?.disconnect() } catch (_: Exception) {}
            try { svc.activeGatt?.close() } catch (_: Exception) {}
            svc.activeGatt = null
            svc.updateForegroundNotification()
            svc.pushBleStateToJs()
        }

        @JavascriptInterface
        fun writeBleBase64(b64Packet: String): Boolean {
            val svc = instance ?: return false
            val bytes = Base64.decode(b64Packet, Base64.DEFAULT)
            return svc.writeBlePacketSync(bytes)
        }

        @JavascriptInterface
        fun launchAppPackage(pkgName: String): Boolean {
            val svc = instance ?: return false
            return try {
                val targetPkg = pkgName.ifBlank { "com.deliveryhero.pandaportal" }
                val launchIntent = svc.packageManager.getLaunchIntentForPackage(targetPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val acc = BilluAccessibilityService.instance
                    if (acc != null) {
                        acc.startActivity(launchIntent)
                    } else {
                        svc.startActivity(launchIntent)
                    }
                    svc.updateFloatingBubbleVisibility(true)
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun bringBilluToFront() {
            instance?.bringBilluActivityToFront()
        }

        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean {
            return BilluAccessibilityService.instance != null
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            val svc = instance ?: return
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            svc.startActivity(intent)
        }

        @JavascriptInterface
        fun saveDownloadBase64(b64Data: String, fileName: String, mimeType: String): Boolean {
            val svc = instance ?: return false
            return try {
                val bytes = Base64.decode(b64Data, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Billu")
                    }
                    val resolver = svc.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Billu")
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(svc, "💾 Saved to Downloads/Billu/$fileName", Toast.LENGTH_SHORT).show()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
