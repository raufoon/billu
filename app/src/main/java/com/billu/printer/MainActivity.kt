package com.billu.printer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        var currentInstance: MainActivity? = null
        private const val REQ_PERMS = 1001
        private const val REQ_FILE_CHOOSER = 1002
    }

    private lateinit var rootContainer: FrameLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this

        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        requestNeededPermissions()
        startBridgeService()
        attachSharedWebView()
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent != null) {
            handleIncomingShareIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        currentInstance = this
        attachSharedWebView()
        BilluBridgeService.instance?.notifyUiVisibility(true)
    }

    override fun onPause() {
        super.onPause()
        // Intentionally do NOT pause WebView timers so background printing & MQTT stay 100% active
        BilluBridgeService.instance?.notifyUiVisibility(false)
    }

    override fun onDestroy() {
        detachSharedWebView()
        if (currentInstance === this) currentInstance = null
        super.onDestroy()
    }

    private fun startBridgeService() {
        val svcIntent = Intent(this, BilluBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
    }

    fun attachSharedWebView() {
        val wv = BilluBridgeService.getOrCreateWebView(this)
        wv.webChromeClient = createWebChromeClient()
        val parent = wv.parent as? ViewGroup
        if (parent !== rootContainer) {
            parent?.removeView(wv)
            rootContainer.removeAllViews()
            rootContainer.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun detachSharedWebView() {
        val wv = BilluBridgeService.sharedWebView ?: return
        val parent = wv.parent as? ViewGroup
        if (parent === rootContainer) {
            rootContainer.removeView(wv)
        }
        BilluBridgeService.restoreServiceContext()
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            // Grant WebRTC Camera access for Peer QR Code Scanning
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // Support <input type="file"> for PDFs, Stamps, Images, and .json Backups
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Confirm")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(message ?: "Input")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_CHOOSER) {
            val res = if (resultCode == Activity.RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            filePathCallback?.onReceiveValue(res)
            filePathCallback = null
        }
    }

    private fun handleIncomingShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val b64 = Base64.encodeToString(sharedText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            BilluBridgeService.instance?.deliverCapturedOrderToJs(b64, bringToFront = true)
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }

        // Prompt once for "Appear on Top" so the Floating 🐼 Print Bubble can be drawn over Foodpanda Partner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val prefs = getSharedPreferences("billu_native_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("asked_overlay_once", false)) {
                prefs.edit().putBoolean("asked_overlay_once", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("Enable Floating Print Button")
                    .setMessage("Allow Billu to display the floating 🐼 Print button on top of Foodpanda Partner.")
                    .setPositiveButton("Enable") { _, _ ->
                        val ovIntent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(ovIntent)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startBridgeService()
        BilluBridgeService.instance?.triggerImmediateBleReconnect()
    }
}
