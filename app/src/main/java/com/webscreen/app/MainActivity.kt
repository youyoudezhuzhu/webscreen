package com.webscreen.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogSize = 0

    private lateinit var rootStatus: TextView
    private lateinit var serverStatus: TextView
    private lateinit var addressView: TextView
    private lateinit var portView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var pinInput: EditText
    private lateinit var autoStartSwitch: Switch

    private var rootGranted = false
    private var rootCheckInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)

        rootStatus = findViewById(R.id.rootStatus)
        serverStatus = findViewById(R.id.serverStatus)
        addressView = findViewById(R.id.addressView)
        portView = findViewById(R.id.portView)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        pinInput = findViewById(R.id.pinInput)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)

        prefs.pin.takeIf { it.isNotEmpty() }?.let { pinInput.setText(it) }
        autoStartSwitch.isChecked = prefs.autoStart
        autoStartSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoStart = checked
        }

        findViewById<Button>(R.id.copyButton).setOnClickListener { copyAddress() }
        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            ServerState.clearLog()
            renderLog()
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        checkRoot()
        refreshFromProcess()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ------------------------------------------------------------------ clicks

    private fun onStartClicked() {
        if (!rootGranted) {
            // The root request is the standard `su` call; the manager shows the
            // dialog the first time. Re-check and try again right away.
            toast(getString(R.string.need_root))
            checkRoot(andThenStart = true)
            return
        }
        val pin = pinInput.text.toString().trim()
        if (pin.isNotEmpty() && (pin.length != 6 || !pin.all { it.isDigit() })) {
            toast(getString(R.string.invalid_pin))
            return
        }
        prefs.pin = pin
        ServerState.clearLog()
        WebScreenService.start(this)
        handler.postDelayed({ refreshFromProcess() }, 800)
    }

    private fun onStopClicked() {
        WebScreenService.stop(this)
        ServerState.setRunning(false)
        handler.postDelayed({ refreshFromProcess() }, 500)
    }

    private fun copyAddress() {
        val address = NetUtils.addressFor(prefs.port)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("webscreen", address))
        toast(getString(R.string.copied, address))
    }

    // ------------------------------------------------------------------ status

    private fun checkRoot(andThenStart: Boolean = false) {
        if (rootCheckInFlight) return
        rootCheckInFlight = true
        Thread {
            val granted = RootShell.hasRoot()
            rootGranted = granted
            rootCheckInFlight = false
            handler.post {
                renderRoot(granted)
                if (granted && andThenStart) {
                    startServerNow()
                }
            }
        }.start()
    }

    private fun startServerNow() {
        val pin = pinInput.text.toString().trim()
        if (pin.isNotEmpty() && (pin.length != 6 || !pin.all { it.isDigit() })) {
            toast(getString(R.string.invalid_pin))
            return
        }
        prefs.pin = pin
        ServerState.clearLog()
        WebScreenService.start(this)
        handler.postDelayed({ refreshFromProcess() }, 800)
    }

    private fun refreshFromProcess() {
        if (ServerState.running) {
            renderServer()
            return
        }
        Thread {
            val running = WebScreenService.detectRunning()
            if (running) {
                ServerState.setRunning(true)
            }
            handler.post { renderServer() }
        }.start()
    }

    private var lastStateProbeAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            renderServer()
            renderLog()
            // While root is missing, keep probing: as soon as the user approves
            // the request in Magisk/KernelSU the start button becomes usable.
            if (!rootGranted) {
                checkRoot()
            }
            // ServerState.running 只是本进程内的缓存标记：服务进程可能被杀，
            // 也可能由别的途径（开机自启、上一次运行的残留）启动，界面就会一直
            // 停在错误的状态上，用户得手动“停止→启动”才能恢复。这里定期拿真实
            // 进程校正一次，界面状态不再需要人工纠正。
            val now = System.currentTimeMillis()
            if (now - lastStateProbeAt >= 2000) {
                lastStateProbeAt = now
                Thread {
                    val pid = ServerState.runningPid()
                    val actuallyRunning = pid != 0
                    if (actuallyRunning != ServerState.running) {
                        ServerState.setRunning(actuallyRunning)
                        ServerState.append(
                            if (actuallyRunning) "[app] 检测到 webscreen 进程 (pid=$pid)，界面状态已同步"
                            else "[app] webscreen 进程已退出，界面状态已同步"
                        )
                    }
                    handler.post { renderServer() }
                }.start()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun renderRoot(granted: Boolean) {
        if (granted) {
            rootStatus.text = getString(R.string.root_granted)
            rootStatus.setTextColor(0xFF6EC7AD.toInt())
        } else {
            rootStatus.text = getString(R.string.need_root)
            rootStatus.setTextColor(0xFFD98B72.toInt())
        }
        startButton.isEnabled = granted
    }

    private fun renderServer() {
        val running = ServerState.running
        serverStatus.text = if (running) {
            getString(R.string.server_running)
        } else {
            getString(R.string.server_stopped)
        }
        serverStatus.setTextColor(if (running) 0xFF6EC7AD.toInt() else 0xFF9E9E9E.toInt())

        portView.text = prefs.port.toString()
        val address = NetUtils.addressFor(prefs.port)
        addressView.text = if (running) address else getString(R.string.address_hint, address)

        startButton.isEnabled = rootGranted && !running
        stopButton.isEnabled = running

        ServerState.lastError?.let {
            if (!running) {
                serverStatus.text = getString(R.string.server_error, it)
                serverStatus.setTextColor(0xFFD98B72.toInt())
            }
        }
    }

    private fun renderLog() {
        val lines = ServerState.snapshot()
        if (lines.size == lastLogSize && logView.text.isNotEmpty()) return
        lastLogSize = lines.size
        logView.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ misc

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        refreshFromProcess()
    }
}
