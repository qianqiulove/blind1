package com.blind.v1

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.net.Uri
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.model.LatLng
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.iflytek.sparkchain.core.LogLvl
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import com.iflytek.sparkchain.core.tts.OnlineTTS
import com.iflytek.sparkchain.core.tts.TTS
import com.iflytek.sparkchain.core.tts.TTSCallbacks
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var viewerImage: ImageView
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvGuidance: TextView

    private lateinit var pageNav: View
    private lateinit var pageMap: View
    private lateinit var pageAssistant: View
    private lateinit var btnTabNav: Button
    private lateinit var btnTabMap: Button
    private lateinit var btnTabAssistant: Button
    private lateinit var btnLocateMe: Button
    private lateinit var etMapDestination: EditText
    private lateinit var btnMapSearch: Button
    private lateinit var btnMapMore: Button
    private lateinit var tvMapStatus: TextView
    private lateinit var tvMapCurrentLocationValue: TextView
    private lateinit var tvMapNearestBlindValue: TextView
    private lateinit var tvMapRouteSummary: TextView
    private lateinit var mapCanvas: FrameLayout
    private var baiduMapView: MapView? = null
    private var baiduMap: BaiduMap? = null
    private var mapModuleReady: Boolean = true
    private var mapViewAttached: Boolean = false
    private lateinit var etAssistantInput: EditText
    private lateinit var btnAssistantSend: Button
    private lateinit var btnAssistantMic: Button
    private lateinit var btnAssistantTtsToggle: Button
    private lateinit var btnFamilyMessage: Button
    private lateinit var tvAssistantVoiceDiag: TextView
    private lateinit var llChatMessages: LinearLayout
    private lateinit var llAssistantSteps: LinearLayout
    private lateinit var svAssistant: ScrollView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    private var wsCamera: WebSocket? = null
    private var wsGuidance: WebSocket? = null
    private var wsAudio: WebSocket? = null
    private var wsViewer: WebSocket? = null
    private var wsAsrProxy: WebSocket? = null

    private var isConnected = false
    private var isNavEnabled = false
    private var openCount = 0
    private var lastGuidanceText: String = ""
    private var currentWsBase: String = ""
    private var currentTab: Int = TAB_NAV

    private var viewerEnabled: Boolean = false
    private var viewerHasFreshFrame: Boolean = false
    private var lastViewerFrameAtMs: Long = 0L
    private var viewerReconnectScheduled = false
    private var viewerMonitorStarted = false
    @Volatile
    private var viewerDecodeBusy: Boolean = false

    private var lastFrameSentAtMs: Long = 0L
    private var lastGuidanceLatencyMs: Long = -1L
    private var lastGuidanceFrameId: Long = 0L
    private var lastCameraQueueBytes: Long = 0L
    private var droppedByQueue: Long = 0L
    private var sentFrames: Long = 0L

    private var audioTrack: AudioTrack? = null
    private var currentLat: Double? = null
    private var currentLng: Double? = null
    private var pendingLocateAfterPermission = false
    private var locatingListener: LocationListener? = null
    private var locatingTimeoutRunnable: Runnable? = null
    private var locatingBestLocation: Location? = null
    private var pendingSpeechAfterPermission = false
    private var sparkReady: Boolean = false
    private var sparkAsr: ASR? = null
    private var sparkAsrListening: Boolean = false
    private var sparkAsrSeq: Int = 0
    private var sparkAsrRecorder: AudioRecord? = null
    private var sparkAsrThread: Thread? = null
    private val sparkAsrWriteEnabled = AtomicBoolean(false)
    private var sparkAsrBestText: String = ""
    private var sparkAsrRetryCount: Int = 0
    private var sparkAsrRetryPending: Boolean = false
    private var sparkAsrTimeoutRunnable: Runnable? = null
    private var proxyAsrRecorder: AudioRecord? = null
    private var proxyAsrThread: Thread? = null
    private val proxyAsrWriteEnabled = AtomicBoolean(false)
    private var proxyAsrBestText: String = ""
    private var sparkTts: OnlineTTS? = null
    private var sparkTtsTrack: AudioTrack? = null
    private var sparkTtsPlaying: Boolean = false
    private var assistantTtsEnabled: Boolean = true
    private val assistantHistory = JSONArray()

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    companion object {
        private const val TAG = "blind-v1"
        private const val TAG_VOICE = "blind-v1-voice"
        private const val PREFS_NAME = "blind_v1_settings"
        private const val KEY_HOST = "backend_host"
        private const val KEY_PORT = "backend_port"
        private const val KEY_ASSISTANT_TTS_ENABLED = "assistant_tts_enabled"
        private const val DEFAULT_HOST = "10.0.2.2"
        private const val DEFAULT_PORT = "8088"

        private const val REQ_CAMERA_PERMISSION = 1001
        private const val REQ_LOCATION_PERMISSION = 1002
        private const val REQ_AUDIO_PERMISSION = 1003

        private const val TAB_NAV = 0
        private const val TAB_MAP = 1
        private const val TAB_ASSISTANT = 2

        private const val FRAME_SEND_INTERVAL_MS = 70L
        private const val CAMERA_JPEG_QUALITY = 35
        private const val FRAME_SEND_INTERVAL_CONGESTED_MS = 120L
        private const val CAMERA_JPEG_QUALITY_CONGESTED = 26
        private const val CAMERA_WS_QUEUE_WARN_BYTES = 128 * 1024L
        private const val CAMERA_WS_QUEUE_HARD_DROP_BYTES = 512 * 1024L

        private const val VIEWER_STALE_TIMEOUT_MS = 2200L
        private const val VIEWER_RECONNECT_DELAY_MS = 1200L
        private const val LOCATE_WINDOW_MS = 5000L
        private const val LOCATE_GOOD_ACCURACY_M = 30f
        private const val LOCATE_MAX_FALLBACK_ACCURACY_M = 80f

        private const val SPARK_TTS_SAMPLE_RATE = 16000
        private const val SPARK_ASR_SAMPLE_RATE = 16000
        private const val SPARK_ASR_RESULT_TIMEOUT_MS = 15000L
        private const val SPARK_ASR_RETRY_DELAY_MS = 700L
        private const val SPARK_ASR_MAX_RETRY = 1
    }

    private fun isUnsupportedMapAbi(): Boolean {
        val abis = Build.SUPPORTED_ABIS?.map { it.lowercase(Locale.US) } ?: emptyList()
        return abis.any { it.contains("x86") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mapModuleReady = !isUnsupportedMapAbi()
        try {
            if (mapModuleReady) {
                SDKInitializer.setAgreePrivacy(applicationContext, true)
                SDKInitializer.initialize(applicationContext)
                SDKInitializer.setCoordType(CoordType.BD09LL)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "baidu sdk init failed", e)
            mapModuleReady = false
        }
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            bindViews()
            initTabs()
            initNavPageActions()
            initMapPageActions()
            initAssistantPageActions()
            ensureCameraPermissionAndStart()
            initAudioTrack()
            initSparkChain()
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate bootstrap failed", e)
            val fallback = TextView(this).apply {
                text = "启动失败，请重新安装并上报日志。\n${e.javaClass.simpleName}: ${e.message}"
                setPadding(32, 48, 32, 48)
                textSize = 16f
            }
            setContentView(fallback)
        }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        viewerImage = findViewById(R.id.viewerImage)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        tvStatus = findViewById(R.id.tvStatus)
        tvGuidance = findViewById(R.id.tvGuidance)

        pageNav = findViewById(R.id.pageNav)
        pageMap = findViewById(R.id.pageMap)
        pageAssistant = findViewById(R.id.pageAssistant)
        btnTabNav = findViewById(R.id.btnTabNav)
        btnTabMap = findViewById(R.id.btnTabMap)
        btnTabAssistant = findViewById(R.id.btnTabAssistant)
        btnLocateMe = findViewById(R.id.btnLocateMe)
        etMapDestination = findViewById(R.id.etMapDestination)
        btnMapSearch = findViewById(R.id.btnMapSearch)
        btnMapMore = findViewById(R.id.btnMapMore)
        tvMapStatus = findViewById(R.id.tvMapStatus)
        tvMapCurrentLocationValue = findViewById(R.id.tvMapCurrentLocationValue)
        tvMapNearestBlindValue = findViewById(R.id.tvMapNearestBlindValue)
        tvMapRouteSummary = findViewById(R.id.tvMapRouteSummary)
        mapCanvas = findViewById(R.id.mapCanvas)
        if (!mapModuleReady) {
            tvMapStatus.text = "Map disabled on x86/x86_64 device."
        }
        etAssistantInput = findViewById(R.id.etAssistantInput)
        btnAssistantSend = findViewById(R.id.btnAssistantSend)
        btnAssistantMic = findViewById(R.id.btnAssistantMic)
        btnAssistantTtsToggle = findViewById(R.id.btnAssistantTtsToggle)
        btnFamilyMessage = findViewById(R.id.btnFamilyMessage)
        tvAssistantVoiceDiag = findViewById(R.id.tvAssistantVoiceDiag)
        llChatMessages = findViewById(R.id.llChatMessages)
        llAssistantSteps = findViewById(R.id.llAssistantSteps)
        svAssistant = findViewById(R.id.svAssistant)

        etHost.setText(prefs.getString(KEY_HOST, DEFAULT_HOST))
        etPort.setText(prefs.getString(KEY_PORT, DEFAULT_PORT))
        assistantTtsEnabled = prefs.getBoolean(KEY_ASSISTANT_TTS_ENABLED, true)
        refreshAssistantTtsToggleUi()
        updateVoiceDiagnostics(
            permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            sparkSdkReady = sparkReady,
            sparkAsrActive = sparkAsrListening,
            detail = "初始化完成，等待SDK启动"
        )
    }

    private fun attachMapViewSafely() {
        if (mapViewAttached && baiduMapView != null) return
        if (!mapModuleReady) {
            baiduMapView = null
            baiduMap = null
            tvMapStatus.text = "Map module disabled."
            return
        }
        try {
            val mv = MapView(this)
            mv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            mapCanvas.removeAllViews()
            mapCanvas.addView(mv)
            baiduMapView = mv
            baiduMap = mv.map
            mapViewAttached = true
        } catch (e: Throwable) {
            Log.e(TAG, "map view attach failed", e)
            baiduMapView = null
            baiduMap = null
            mapViewAttached = false
            mapModuleReady = false
            tvMapStatus.text = "Map init failed and has been disabled."
        }
    }
    private fun initTabs() {
        btnTabNav.setOnClickListener { switchTab(TAB_NAV) }
        btnTabMap.setOnClickListener { switchTab(TAB_MAP) }
        btnTabAssistant.setOnClickListener { switchTab(TAB_ASSISTANT) }
        switchTab(TAB_NAV)
        setMapStatus("状态：等待定位...")
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        pageMap.visibility = if (tab == TAB_MAP) View.VISIBLE else View.GONE
        pageNav.visibility = if (tab == TAB_NAV) View.VISIBLE else View.GONE
        pageAssistant.visibility = if (tab == TAB_ASSISTANT) View.VISIBLE else View.GONE
        if (tab == TAB_MAP) {
            attachMapViewSafely()
        }

        val activeBg = ColorStateList.valueOf(0xFF4DABF7.toInt())
        val inactiveBg = ColorStateList.valueOf(0xFFE9ECEF.toInt())
        val activeText = 0xFFFFFFFF.toInt()
        val inactiveText = 0xFF333333.toInt()
        btnTabNav.backgroundTintList = if (tab == TAB_NAV) activeBg else inactiveBg
        btnTabMap.backgroundTintList = if (tab == TAB_MAP) activeBg else inactiveBg
        btnTabAssistant.backgroundTintList = if (tab == TAB_ASSISTANT) activeBg else inactiveBg
        btnTabNav.setTextColor(if (tab == TAB_NAV) activeText else inactiveText)
        btnTabMap.setTextColor(if (tab == TAB_MAP) activeText else inactiveText)
        btnTabAssistant.setTextColor(if (tab == TAB_ASSISTANT) activeText else inactiveText)
    }

    private fun initNavPageActions() {
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectSockets() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { callNavCommand("/api/nav/start") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { callNavCommand("/api/nav/stop") }
        findViewById<Button>(R.id.btnTrafficTest).setOnClickListener {
            callNavCommand("/api/traffic-test/start")
        }
        findViewById<Button>(R.id.btnComprehensiveTest).setOnClickListener {
            callNavCommand("/api/nav/start?mode=blind_nav")
        }
        findViewById<Button>(R.id.btnAiReserved).setOnClickListener {
            switchTab(TAB_ASSISTANT)
        }
        findViewById<Button>(R.id.btnMapReserved).setOnClickListener {
            switchTab(TAB_MAP)
        }
        val btnDebug = findViewById<Button>(R.id.btnDebugOverlay)
        btnDebug.setOnClickListener {
            viewerEnabled = !viewerEnabled
            btnDebug.text = if (viewerEnabled) "Server Overlay On" else "Server Overlay Off"
            if (viewerEnabled) {
                viewerHasFreshFrame = false
                refreshVideoLayerMode()
                if (wsViewer == null && currentWsBase.isNotBlank()) {
                    connectViewerSocket(currentWsBase)
                }
                monitorViewerStall()
            } else {
                viewerHasFreshFrame = false
                refreshVideoLayerMode()
                viewerImage.setImageBitmap(null)
            }
        }
    }

    private fun initMapPageActions() {
        btnLocateMe.setOnClickListener { locateAndReverseGeocode() }
        btnMapSearch.setOnClickListener { geocodeThenRoute() }
        btnMapMore.setOnClickListener { showMorePoiDialog() }
    }

    private fun initAssistantPageActions() {
        btnAssistantSend.setOnClickListener { sendAssistantMessage() }
        btnAssistantMic.setOnClickListener { startVoiceInput() }
        btnAssistantTtsToggle.setOnClickListener {
            assistantTtsEnabled = !assistantTtsEnabled
            prefs.edit().putBoolean(KEY_ASSISTANT_TTS_ENABLED, assistantTtsEnabled).apply()
            refreshAssistantTtsToggleUi()
            if (!assistantTtsEnabled) {
                stopSparkTtsPlayback()
            }
            val msg = if (assistantTtsEnabled) "已开启助手朗读" else "已关闭助手朗读"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        btnFamilyMessage.setOnClickListener {
            etAssistantInput.setText("请帮我给家属发送消息：")
            etAssistantInput.setSelection(etAssistantInput.text?.length ?: 0)
            Toast.makeText(this, "给家属发消息功能暂未启用。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAssistantTtsToggleUi() {
        if (!::btnAssistantTtsToggle.isInitialized) return
        btnAssistantTtsToggle.text =
            getString(if (assistantTtsEnabled) R.string.assistant_tts_on else R.string.assistant_tts_off)
        val bgColor = if (assistantTtsEnabled) 0xFF4DABF7.toInt() else 0xFFADB5BD.toInt()
        btnAssistantTtsToggle.backgroundTintList = ColorStateList.valueOf(bgColor)
        btnAssistantTtsToggle.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == REQ_LOCATION_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                if (pendingLocateAfterPermission) {
                    pendingLocateAfterPermission = false
                    locateAndReverseGeocode()
                }
            } else {
                pendingLocateAfterPermission = false
                setMapStatus("状态：定位权限未授予")
            }
            return
        }
        if (requestCode == REQ_AUDIO_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                updateVoiceDiagnostics(
                    permissionOk = true,
                    sparkSdkReady = true,
                    sparkAsrActive = sparkAsrListening,
                    detail = "录音权限已授予"
                )
                if (pendingSpeechAfterPermission) {
                    pendingSpeechAfterPermission = false
                    startProxyAsrRecognition(resetRetry = true)
                }
            } else {
                pendingSpeechAfterPermission = false
                updateVoiceDiagnostics(
                    permissionOk = false,
                    sparkSdkReady = true,
                    sparkAsrActive = false,
                    detail = "录音权限未开启"
                )
                Toast.makeText(this, "语音权限未授予。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVoiceInput() {
        val permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (permissionOk) {
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = true,
                sparkAsrActive = sparkAsrListening,
                detail = "开始语音识别（后端代理）"
            )
            startProxyAsrRecognition(resetRetry = true)
        } else {
            updateVoiceDiagnostics(
                permissionOk = false,
                sparkSdkReady = true,
                sparkAsrActive = false,
                detail = "请求录音权限"
            )
            pendingSpeechAfterPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION)
        }
    }

    private fun startProxyAsrRecognition(resetRetry: Boolean) {
        if (sparkAsrListening) {
            Toast.makeText(this, "语音识别进行中，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isNetworkAvailable()) {
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = true,
                sparkAsrActive = false,
                detail = "当前网络不可用，请检查 Wi-Fi/移动网络后重试"
            )
            Toast.makeText(this, "网络不可用，请检查网络后重试。", Toast.LENGTH_SHORT).show()
            return
        }
        if (resetRetry) {
            sparkAsrRetryCount = 0
            sparkAsrRetryPending = false
        }
        proxyAsrBestText = ""
        sparkAsrListening = true
        sparkAsrWriteEnabled.set(true)

        val wsBase = currentBaseUrl().replace("http://", "ws://").replace("https://", "wss://")
        val req = Request.Builder().url("$wsBase/ws/asr_proxy").build()
        wsAsrProxy?.close(1000, "restart")
        wsAsrProxy = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val startPayload = JSONObject()
                    .put("type", "start")
                    .put("sample_rate", SPARK_ASR_SAMPLE_RATE)
                    .put("language", "zh_cn")
                    .put("domain", "iat")
                    .put("accent", "mandarin")
                    .put("dwa", "wpgs")
                    .put("vad_eos", 5000)
                webSocket.send(startPayload.toString())
                startProxyAsrRecorder(webSocket)
                scheduleSparkAsrResultTimeout()
                updateVoiceDiagnostics(
                    permissionOk = true,
                    sparkSdkReady = true,
                    sparkAsrActive = true,
                    detail = if (sparkAsrRetryCount > 0) "重试中（第${sparkAsrRetryCount}次），请开始说话" else "录音中，请开始说话"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "ready" -> {
                            updateVoiceDiagnostics(
                                permissionOk = true,
                                sparkSdkReady = true,
                                sparkAsrActive = true,
                                detail = "语音代理已就绪，正在录音"
                            )
                        }

                        "partial" -> {
                            val p = obj.optString("text", "").trim()
                            if (p.isNotBlank()) proxyAsrBestText = p
                        }

                        "final" -> {
                            val finalText = obj.optString("text", "").trim().ifBlank { proxyAsrBestText.trim() }
                            stopProxyAsrRecognition(sendStop = false)
                            sparkAsrRetryCount = 0
                            sparkAsrRetryPending = false
                            if (finalText.isNotBlank()) {
                                runOnUiThread { bindAsrTextAndSend(finalText) }
                            } else {
                                updateVoiceDiagnostics(
                                    permissionOk = true,
                                    sparkSdkReady = true,
                                    sparkAsrActive = false,
                                    detail = "识别结束但无文本"
                                )
                            }
                        }

                        "error" -> {
                            val code = obj.optInt("code", -1)
                            val msg = obj.optString("message", "未知错误")
                            handleProxyAsrError(code, msg)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG_VOICE, "proxy asr parse failed: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleProxyAsrError(18804, t.message ?: "network_failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (sparkAsrListening) {
                    handleProxyAsrError(code, reason.ifBlank { "connection_closed" })
                }
            }
        })
    }

    private fun startProxyAsrRecorder(webSocket: WebSocket) {
        stopProxyAsrRecorder()
        val minBuffer = AudioRecord.getMinBufferSize(
            SPARK_ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            handleProxyAsrError(-2, "录音缓冲区初始化失败")
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SPARK_ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            handleProxyAsrError(-3, "麦克风初始化失败")
            return
        }
        proxyAsrRecorder = recorder
        proxyAsrWriteEnabled.set(true)
        proxyAsrThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ByteArray(minBuffer)
                recorder.startRecording()
                while (proxyAsrWriteEnabled.get() && sparkAsrListening) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val payload = if (read == buffer.size) buffer else buffer.copyOf(read)
                    if (!webSocket.send(payload.toByteString())) {
                        break
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG_VOICE, "proxy recorder failed", e)
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                try {
                    recorder.release()
                } catch (_: Throwable) {
                }
                if (proxyAsrRecorder === recorder) proxyAsrRecorder = null
            }
        }.also { it.start() }
    }

    private fun stopProxyAsrRecorder() {
        proxyAsrWriteEnabled.set(false)
        proxyAsrThread?.let { th ->
            if (th.isAlive) {
                try {
                    th.join(200)
                } catch (_: Throwable) {
                }
            }
        }
        proxyAsrThread = null
        proxyAsrRecorder?.let { recorder ->
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
        }
        proxyAsrRecorder = null
    }

    private fun stopProxyAsrRecognition(sendStop: Boolean) {
        if (!sparkAsrListening && wsAsrProxy == null) return
        sparkAsrListening = false
        sparkAsrWriteEnabled.set(false)
        proxyAsrWriteEnabled.set(false)
        cancelSparkAsrResultTimeout()
        stopProxyAsrRecorder()
        if (sendStop) {
            try {
                wsAsrProxy?.send(JSONObject().put("type", "stop").toString())
            } catch (_: Throwable) {
            }
        }
        wsAsrProxy?.close(1000, "stop")
        wsAsrProxy = null
    }

    private fun handleProxyAsrError(code: Int, msg: String) {
        Log.w(TAG_VOICE, "proxy asr failed code=$code msg=$msg")
        stopProxyAsrRecognition(sendStop = false)
        if (shouldRetrySparkAsr(code) && sparkAsrRetryCount < SPARK_ASR_MAX_RETRY && !sparkAsrRetryPending) {
            sparkAsrRetryPending = true
            sparkAsrRetryCount += 1
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = true,
                sparkAsrActive = false,
                detail = "网络波动（code=$code），${SPARK_ASR_RETRY_DELAY_MS}ms 后自动重试第${sparkAsrRetryCount}次"
            )
            mainHandler.postDelayed({
                sparkAsrRetryPending = false
                if (currentTab == TAB_ASSISTANT &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ) {
                    startProxyAsrRecognition(resetRetry = false)
                }
            }, SPARK_ASR_RETRY_DELAY_MS)
            return
        }
        sparkAsrRetryCount = 0
        sparkAsrRetryPending = false
        updateVoiceDiagnostics(
            permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            sparkSdkReady = true,
            sparkAsrActive = false,
            detail = "识别失败：code=$code, msg=$msg"
        )
        runOnUiThread { Toast.makeText(this@MainActivity, "语音识别失败：$msg", Toast.LENGTH_SHORT).show() }
    }

    private fun startSparkAsrRecognition(resetRetry: Boolean) {
        if (!sparkReady) {
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = false,
                sparkAsrActive = false,
                detail = "讯飞SDK未初始化，请检查三元组配置"
            )
            Toast.makeText(this, "讯飞SDK未初始化，请检查 IFLYTEK_* 配置。", Toast.LENGTH_LONG).show()
            return
        }
        if (sparkAsrListening) {
            Toast.makeText(this, "语音识别进行中，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isNetworkAvailable()) {
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = "当前网络不可用，请检查 Wi-Fi/移动网络后重试"
            )
            Toast.makeText(this, "网络不可用，请检查网络后重试。", Toast.LENGTH_SHORT).show()
            return
        }
        if (resetRetry) {
            sparkAsrRetryCount = 0
            sparkAsrRetryPending = false
        }
        ensureSparkAsrInstance()
        sparkAsrBestText = ""
        sparkAsrSeq += 1
        val asr = sparkAsr
        if (asr == null) {
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = "ASR对象创建失败"
            )
            return
        }
        try {
            asr.language("zh_cn")
            asr.domain("iat")
            asr.accent("mandarin")
            asr.vinfo(true)
            asr.dwa("wpgs")
            val ret = asr.start("blind_${sparkAsrSeq}")
            if (ret != 0) {
                updateVoiceDiagnostics(
                    permissionOk = true,
                    sparkSdkReady = sparkReady,
                    sparkAsrActive = false,
                    detail = "ASR启动失败，错误码=$ret"
                )
                Toast.makeText(this, "语音识别启动失败：$ret", Toast.LENGTH_SHORT).show()
                return
            }
            sparkAsrListening = true
            sparkAsrWriteEnabled.set(true)
            startSparkAsrRecorder()
            scheduleSparkAsrResultTimeout()
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = true,
                sparkAsrActive = true,
                detail = if (sparkAsrRetryCount > 0) "重试中（第${sparkAsrRetryCount}次），请开始说话" else "录音中，请开始说话"
            )
            Toast.makeText(this, "开始识别，请说话…", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            sparkAsrListening = false
            sparkAsrWriteEnabled.set(false)
            stopSparkAsrRecorder()
            cancelSparkAsrResultTimeout()
            Log.e(TAG_VOICE, "startSparkAsrRecognition failed", e)
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = "启动识别异常：${e.javaClass.simpleName}"
            )
        }
    }

    private fun ensureSparkAsrInstance() {
        if (sparkAsr != null) return
        sparkAsr = ASR().apply {
            registerCallbacks(object : AsrCallbacks {
                override fun onResult(asrResult: ASR.ASRResult, o: Any?) {
                    val text = asrResult.bestMatchText?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        sparkAsrBestText = text
                    }
                    if (asrResult.status == 2) {
                        sparkAsrListening = false
                        sparkAsrWriteEnabled.set(false)
                        stopSparkAsrRecorder()
                        cancelSparkAsrResultTimeout()
                        val finalText = sparkAsrBestText.trim()
                        updateVoiceDiagnostics(
                            permissionOk = true,
                            sparkSdkReady = sparkReady,
                            sparkAsrActive = false,
                            detail = if (finalText.isBlank()) "识别结束但无文本" else "识别成功：$finalText"
                        )
                        if (finalText.isNotBlank()) {
                            runOnUiThread {
                                bindAsrTextAndSend(finalText)
                            }
                        }
                        sparkAsrBestText = ""
                        sparkAsrRetryCount = 0
                        sparkAsrRetryPending = false
                    }
                }

                override fun onError(asrError: ASR.ASRError, o: Any?) {
                    sparkAsrListening = false
                    sparkAsrWriteEnabled.set(false)
                    stopSparkAsrRecorder()
                    cancelSparkAsrResultTimeout()
                    val code = asrError.code
                    val msg = asrError.errMsg ?: "未知错误"
                    val detail = "识别失败：code=$code, msg=$msg"
                    Log.w(TAG_VOICE, detail)
                    if (shouldRetrySparkAsr(code) && sparkAsrRetryCount < SPARK_ASR_MAX_RETRY && !sparkAsrRetryPending) {
                        sparkAsrRetryPending = true
                        sparkAsrRetryCount += 1
                        updateVoiceDiagnostics(
                            permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                            sparkSdkReady = sparkReady,
                            sparkAsrActive = false,
                            detail = "网络波动（code=$code），${SPARK_ASR_RETRY_DELAY_MS}ms 后自动重试第${sparkAsrRetryCount}次"
                        )
                        mainHandler.postDelayed({
                            sparkAsrRetryPending = false
                            if (currentTab == TAB_ASSISTANT &&
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                                sparkReady
                            ) {
                                startSparkAsrRecognition(resetRetry = false)
                            }
                        }, SPARK_ASR_RETRY_DELAY_MS)
                        return
                    }
                    updateVoiceDiagnostics(
                        permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                        sparkSdkReady = sparkReady,
                        sparkAsrActive = false,
                        detail = detail
                    )
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "语音识别失败：$msg", Toast.LENGTH_SHORT).show()
                    }
                    sparkAsrBestText = ""
                    sparkAsrRetryCount = 0
                    sparkAsrRetryPending = false
                }

                override fun onBeginOfSpeech() {
                    updateVoiceDiagnostics(
                        permissionOk = true,
                        sparkSdkReady = sparkReady,
                        sparkAsrActive = true,
                        detail = "检测到语音输入"
                    )
                }

                override fun onEndOfSpeech() {
                    updateVoiceDiagnostics(
                        permissionOk = true,
                        sparkSdkReady = sparkReady,
                        sparkAsrActive = true,
                        detail = "语音输入结束，等待最终结果"
                    )
                }
            })
        }
    }

    private fun startSparkAsrRecorder() {
        stopSparkAsrRecorder()
        val minBuffer = AudioRecord.getMinBufferSize(
            SPARK_ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = "录音缓冲区初始化失败"
            )
            stopSparkAsr(true)
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SPARK_ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = "麦克风初始化失败"
            )
            stopSparkAsr(true)
            return
        }
        sparkAsrRecorder = recorder
        sparkAsrThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ByteArray(minBuffer)
                recorder.startRecording()
                while (sparkAsrWriteEnabled.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val payload = if (read == buffer.size) buffer.clone() else buffer.copyOf(read)
                    val ret = sparkAsr?.write(payload) ?: -1
                    if (ret != 0) {
                        Log.w(TAG_VOICE, "asr.write failed ret=$ret")
                        sparkAsrWriteEnabled.set(false)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG_VOICE, "spark recorder thread failed", t)
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                recorder.release()
                if (sparkAsrRecorder === recorder) {
                    sparkAsrRecorder = null
                }
            }
        }.also { it.start() }
    }

    private fun stopSparkAsrRecorder() {
        sparkAsrWriteEnabled.set(false)
        sparkAsrThread?.let { th ->
            if (th.isAlive) {
                try {
                    th.join(200)
                } catch (_: Throwable) {
                }
            }
        }
        sparkAsrThread = null
        sparkAsrRecorder?.let { recorder ->
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
        }
        sparkAsrRecorder = null
    }

    private fun stopSparkAsr(immediate: Boolean) {
        if (!sparkAsrListening && !sparkAsrWriteEnabled.get()) return
        sparkAsrListening = false
        sparkAsrWriteEnabled.set(false)
        sparkAsrRetryPending = false
        cancelSparkAsrResultTimeout()
        stopSparkAsrRecorder()
        try {
            sparkAsr?.stop(immediate)
        } catch (e: Throwable) {
            Log.w(TAG_VOICE, "sparkAsr stop failed", e)
        }
    }

    private fun scheduleSparkAsrResultTimeout() {
        cancelSparkAsrResultTimeout()
        val runnable = Runnable {
            if (!sparkAsrListening) return@Runnable
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = "识别超时，已自动停止，请重试"
            )
            stopProxyAsrRecognition(sendStop = true)
        }
        sparkAsrTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, SPARK_ASR_RESULT_TIMEOUT_MS)
    }

    private fun cancelSparkAsrResultTimeout() {
        sparkAsrTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        sparkAsrTimeoutRunnable = null
    }

    private fun shouldRetrySparkAsr(code: Int): Boolean {
        return code == 18804 || code == 18801
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateVoiceDiagnostics(
        permissionOk: Boolean,
        sparkSdkReady: Boolean,
        sparkAsrActive: Boolean,
        detail: String
    ) {
        val status = "语音诊断：permission_ok=$permissionOk, spark_sdk_ready=$sparkSdkReady, spark_asr_active=$sparkAsrActive"
        runOnUiThread {
            tvAssistantVoiceDiag.text = "$status\n详情：$detail"
        }
        Log.i(TAG_VOICE, "$status | detail=$detail")
    }

    private fun hasSparkCredentials(): Boolean {
        return BuildConfig.IFLYTEK_APP_ID.isNotBlank()
            && BuildConfig.IFLYTEK_API_KEY.isNotBlank()
            && BuildConfig.IFLYTEK_API_SECRET.isNotBlank()
    }

    private fun initSparkChain() {
        if (!hasSparkCredentials()) {
            sparkReady = false
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = false,
                sparkAsrActive = false,
                detail = "未配置 IFLYTEK_APP_ID / IFLYTEK_API_KEY / IFLYTEK_API_SECRET"
            )
            return
        }
        try {
            val config = SparkChainConfig.builder()
                .appID(BuildConfig.IFLYTEK_APP_ID)
                .apiKey(BuildConfig.IFLYTEK_API_KEY)
                .apiSecret(BuildConfig.IFLYTEK_API_SECRET)
                .logLevel(LogLvl.VERBOSE.value)
            val ret = SparkChain.getInst().init(applicationContext, config)
            sparkReady = ret == 0
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = if (sparkReady) "讯飞SDK初始化成功" else "讯飞SDK初始化失败，错误码=$ret"
            )
        } catch (e: Throwable) {
            sparkReady = false
            Log.e(TAG_VOICE, "SparkChain init failed", e)
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = false,
                sparkAsrActive = false,
                detail = "讯飞SDK初始化异常：${e.javaClass.simpleName}"
            )
        }
    }

    private fun bindAsrTextAndSend(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        updateVoiceDiagnostics(
            permissionOk = true,
            sparkSdkReady = sparkReady,
            sparkAsrActive = false,
            detail = "识别成功：$t"
        )
        etAssistantInput.setText(t)
        etAssistantInput.setSelection(etAssistantInput.text?.length ?: 0)
        sendAssistantMessage()
    }

    private fun sendAssistantMessage() {
        val text = etAssistantInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "请输入消息。", Toast.LENGTH_SHORT).show()
            return
        }
        val userBubble = "你：$text"
        addAssistantBubble(userBubble, isUser = true)
        etAssistantInput.setText("")
        saveEndpoint()

        val payload = JSONObject().put("message", text)
        val loc = currentLocationForAssistant()
        if (loc != null) {
            payload.put("user_location", loc)
        } else {
            payload.put("user_location", JSONObject.NULL)
        }
        payload.put("chat_history", JSONArray(assistantHistory.toString()))

        postJson("/api/assistant/chat", payload) { json, err ->
            if (err != null) {
                addAssistantBubble("助手：请求失败，$err", isUser = false)
                return@postJson
            }
            if (json == null) {
                addAssistantBubble("助手：服务返回空响应。", isUser = false)
                return@postJson
            }
            val content = json.optString("content", "").ifBlank { "暂时没有可用回复。" }
            val status = json.optString("status", "ok")
            val error = json.optString("error", "")
            if (status != "ok" && error.isNotBlank()) {
                addAssistantBubble("助手：$content\n($error)", isUser = false)
            } else {
                addAssistantBubble("助手：$content", isUser = false)
            }
            speakAssistantText(content)
            val tools = json.optJSONArray("tool_history") ?: JSONArray()
            renderAssistantToolHistory(tools)

            assistantHistory.put(JSONObject().put("role", "user").put("content", text))
            assistantHistory.put(JSONObject().put("role", "assistant").put("content", content))
            trimAssistantHistory()
        }
    }

    private fun speakAssistantText(text: String) {
        if (currentTab != TAB_ASSISTANT) return
        if (!assistantTtsEnabled) return
        if (!sparkReady) return
        val content = text.trim()
        if (content.isEmpty()) return
        try {
            stopSparkTtsPlayback()
            val onlineTts = OnlineTTS("xiaoyan").apply {
                speed(50)
                pitch(50)
                volume(50)
                bgs(0)
                registerCallbacks(object : TTSCallbacks {
                    override fun onResult(result: TTS.TTSResult, o: Any?) {
                        val audio = result.data ?: return
                        playSparkTtsChunk(audio)
                        if (result.status == 2) {
                            stopSparkTtsPlayback()
                        }
                    }

                    override fun onError(ttsError: TTS.TTSError, o: Any?) {
                        stopSparkTtsPlayback()
                        val msg = "助手朗读失败：code=${ttsError.code}, msg=${ttsError.errMsg}"
                        Log.w(TAG_VOICE, msg)
                    }
                })
            }
            sparkTts = onlineTts
            val ret = onlineTts.aRun(content)
            if (ret != 0) {
                stopSparkTtsPlayback()
                Log.w(TAG_VOICE, "spark tts start failed ret=$ret")
            }
        } catch (e: Throwable) {
            stopSparkTtsPlayback()
            Log.e(TAG_VOICE, "speakAssistantText failed", e)
        }
    }

    private fun playSparkTtsChunk(audio: ByteArray) {
        if (audio.isEmpty()) return
        if (sparkTtsTrack == null) {
            val minBuffer = AudioTrack.getMinBufferSize(
                SPARK_TTS_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return
            sparkTtsTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SPARK_TTS_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
                AudioTrack.MODE_STREAM
            )
        }
        try {
            if (!sparkTtsPlaying) {
                sparkTtsTrack?.play()
                sparkTtsPlaying = true
            }
            sparkTtsTrack?.write(audio, 0, audio.size)
        } catch (e: Throwable) {
            Log.e(TAG_VOICE, "playSparkTtsChunk failed", e)
        }
    }

    private fun stopSparkTtsPlayback() {
        sparkTts = null
        try {
            sparkTtsTrack?.stop()
        } catch (_: Throwable) {
        }
        try {
            sparkTtsTrack?.release()
        } catch (_: Throwable) {
        }
        sparkTtsTrack = null
        sparkTtsPlaying = false
    }

    private fun releaseSparkChain() {
        stopSparkAsr(true)
        stopSparkTtsPlayback()
        sparkAsr = null
        try {
            SparkChain.getInst().unInit()
        } catch (e: Throwable) {
            Log.w(TAG_VOICE, "SparkChain unInit failed", e)
        }
        sparkReady = false
    }

    private fun currentLocationForAssistant(): JSONObject? {
        val lat = currentLat ?: return null
        val lng = currentLng ?: return null
        return JSONObject().put("lat", lat).put("lng", lng)
    }

    private fun trimAssistantHistory(maxItems: Int = 16) {
        if (assistantHistory.length() <= maxItems) return
        val trimmed = JSONArray()
        val start = assistantHistory.length() - maxItems
        for (i in start until assistantHistory.length()) {
            trimmed.put(assistantHistory.optJSONObject(i))
        }
        while (assistantHistory.length() > 0) {
            assistantHistory.remove(assistantHistory.length() - 1)
        }
        for (i in 0 until trimmed.length()) {
            assistantHistory.put(trimmed.optJSONObject(i))
        }
    }

    private fun addAssistantBubble(text: String, isUser: Boolean) {
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFF1F2933.toInt())
            textSize = 14f
            setPadding(20, 14, 20, 14)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(if (isUser) 0xFF4DABF7.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        wrapper.addView(bubble)
        llChatMessages.addView(wrapper)
        scrollAssistantToBottom()
    }

    private fun renderAssistantToolHistory(toolHistory: JSONArray) {
        llAssistantSteps.removeAllViews()
        for (i in 0 until toolHistory.length()) {
            val step = toolHistory.optJSONObject(i) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    cornerRadius = 18f
                    setColor(0xFFF8FAFC.toInt())
                }
            }
            val stepNo = step.optInt("step", i + 1)
            val action = step.optString("action", "unknown")
            val reasoning = step.optString("reasoning", "")
            val params = step.optJSONObject("params")
            val result = step.optJSONObject("result")

            card.addView(TextView(this).apply {
                text = "Step $stepNo - Tool: $action"
                setTextColor(0xFF0F172A.toInt())
                textSize = 14f
            })
            if (reasoning.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "Reasoning: $reasoning"
                    setTextColor(0xFF334155.toInt())
                    textSize = 13f
                })
            }
            if (params != null) {
                card.addView(TextView(this).apply {
                    text = "Params: ${params.toString()}"
                    setTextColor(0xFF475569.toInt())
                    textSize = 12f
                })
            }
            if (result != null) {
                card.addView(TextView(this).apply {
                    text = "Observation: ${result.toString()}"
                    setTextColor(0xFF334155.toInt())
                    textSize = 12f
                })
            }
            llAssistantSteps.addView(card)
        }
        scrollAssistantToBottom()
    }

    private fun scrollAssistantToBottom() {
        svAssistant.post {
            svAssistant.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(384, 216))
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image -> onCameraFrame(image) }
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                updateStatus("Camera ready")
            } catch (e: Exception) {
                Log.e(TAG, "bind camera failed", e)
                updateStatus("Camera bind failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCameraFrame(image: ImageProxy) {
        try {
            if (!isConnected) return
            val camWs = wsCamera ?: return
            val now = System.currentTimeMillis()
            val queueBytes = camWs.queueSize()
            lastCameraQueueBytes = queueBytes
            if (queueBytes > CAMERA_WS_QUEUE_HARD_DROP_BYTES) {
                droppedByQueue += 1
                return
            }
            val sendIntervalMs = if (queueBytes > CAMERA_WS_QUEUE_WARN_BYTES) {
                FRAME_SEND_INTERVAL_CONGESTED_MS
            } else {
                FRAME_SEND_INTERVAL_MS
            }
            if (now - lastFrameSentAtMs < sendIntervalMs) return
            val jpegQuality = if (queueBytes > CAMERA_WS_QUEUE_WARN_BYTES) {
                CAMERA_JPEG_QUALITY_CONGESTED
            } else {
                CAMERA_JPEG_QUALITY
            }
            val jpeg = ImageProxyUtils.toJpegBytes(image, quality = jpegQuality) ?: return
            lastFrameSentAtMs = now
            if (camWs.send(jpeg.toByteString())) {
                sentFrames += 1
            } else {
                droppedByQueue += 1
            }
        } catch (e: Exception) {
            Log.e(TAG, "frame send failed", e)
        } finally {
            image.close()
        }
    }

    private fun connectSockets() {
        saveEndpoint()
        closeSockets()
        isConnected = false
        openCount = 0
        lastGuidanceLatencyMs = -1L
        lastGuidanceFrameId = 0L
        lastCameraQueueBytes = 0L
        droppedByQueue = 0L
        sentFrames = 0L
        val baseHttp = currentBaseUrl()
        val baseWs = baseHttp.replace("http://", "ws://").replace("https://", "wss://")
        currentWsBase = baseWs

        wsCamera = httpClient.newWebSocket(
            Request.Builder().url("$baseWs/ws/camera").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openCount += 1
                    if (openCount >= 3) isConnected = true
                    runOnUiThread { updateStatus("Camera WS connected ($openCount/4)") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "camera ws failed", t)
                    isConnected = false
                    runOnUiThread { updateStatus("Camera WS failed: ${t.message}") }
                }
            }
        )

        wsGuidance = httpClient.newWebSocket(
            Request.Builder().url("$baseWs/ws/guidance").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openCount += 1
                    if (openCount >= 3) isConnected = true
                    runOnUiThread { updateStatus("Guidance WS connected ($openCount/4)") }
                    webSocket.send("hello")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = JSONObject(text)
                        val guidance = obj.optString("guidance_text", "").trim()
                        val state = obj.optString("state", "unknown")
                        val traffic = obj.optString("traffic_light", "unknown")
                        val frameId = obj.optLong("frame_id", 0L)
                        val tsSec = obj.optDouble("timestamp", 0.0)
                        if (tsSec > 0.0) {
                            val latency = System.currentTimeMillis() - (tsSec * 1000.0).toLong()
                            lastGuidanceLatencyMs = latency.coerceAtLeast(0L)
                        }
                        if (frameId > 0L) {
                            lastGuidanceFrameId = frameId
                        }
                        if (guidance.isNotEmpty()) {
                            lastGuidanceText = guidance
                        }
                        runOnUiThread {
                            tvGuidance.text = "Guidance: $lastGuidanceText"
                            val qKb = lastCameraQueueBytes / 1024L
                            val latencyText = if (lastGuidanceLatencyMs >= 0L) "${lastGuidanceLatencyMs}ms" else "-"
                            updateStatus(
                                "State=$state traffic=$traffic latency=$latencyText frame=$lastGuidanceFrameId " +
                                    "upQ=${qKb}KB sent=$sentFrames drop=$droppedByQueue"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "guidance json parse failed", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "guidance ws failed", t)
                    isConnected = false
                    runOnUiThread { updateStatus("Guidance WS failed: ${t.message}") }
                }
            }
        )

        wsAudio = httpClient.newWebSocket(
            Request.Builder().url("$baseWs/ws/audio").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openCount += 1
                    if (openCount >= 3) isConnected = true
                    runOnUiThread { updateStatus("Audio WS connected ($openCount/4)") }
                    webSocket.send("hello")
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    audioTrack?.write(bytes.toByteArray(), 0, bytes.size)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "audio ws failed", t)
                    runOnUiThread { updateStatus("Audio WS failed: ${t.message}") }
                }
            }
        )

        connectViewerSocket(baseWs)
        monitorViewerStall()
        updateStatus("Sockets connecting")
    }

    private fun connectViewerSocket(baseWs: String) {
        wsViewer?.close(1000, "reconnect")
        wsViewer = httpClient.newWebSocket(
            Request.Builder().url("$baseWs/ws/viewer").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openCount += 1
                    if (openCount >= 3) isConnected = true
                    viewerReconnectScheduled = false
                    runOnUiThread { updateStatus("Viewer WS connected ($openCount/4)") }
                    webSocket.send("hello")
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    lastViewerFrameAtMs = System.currentTimeMillis()
                    viewerHasFreshFrame = true
                    runOnUiThread { refreshVideoLayerMode() }
                    if (!viewerEnabled || viewerDecodeBusy) return
                    viewerDecodeBusy = true
                    try {
                        val arr = bytes.toByteArray()
                        val bmp = BitmapFactory.decodeByteArray(arr, 0, arr.size)
                        if (bmp == null) {
                            viewerDecodeBusy = false
                            return
                        }
                        runOnUiThread {
                            try {
                                if (viewerEnabled) viewerImage.setImageBitmap(bmp)
                            } finally {
                                viewerDecodeBusy = false
                            }
                        }
                    } catch (_: Exception) {
                        viewerDecodeBusy = false
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    wsViewer = null
                    scheduleViewerReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "viewer ws failed", t)
                    wsViewer = null
                    runOnUiThread { updateStatus("Viewer WS failed: ${t.message}") }
                    scheduleViewerReconnect()
                }
            }
        )
    }

    private fun scheduleViewerReconnect() {
        if (viewerReconnectScheduled || !viewerEnabled || currentWsBase.isBlank()) return
        viewerReconnectScheduled = true
        mainHandler.postDelayed({
            viewerReconnectScheduled = false
            if (viewerEnabled && wsViewer == null && currentWsBase.isNotBlank()) {
                connectViewerSocket(currentWsBase)
            }
        }, VIEWER_RECONNECT_DELAY_MS)
    }

    private fun monitorViewerStall() {
        if (viewerMonitorStarted) return
        viewerMonitorStarted = true
        val runner = object : Runnable {
            override fun run() {
                if (!viewerMonitorStarted) return
                if (viewerEnabled) {
                    val now = System.currentTimeMillis()
                    if (lastViewerFrameAtMs > 0L && (now - lastViewerFrameAtMs) > VIEWER_STALE_TIMEOUT_MS) {
                        viewerHasFreshFrame = false
                        runOnUiThread { refreshVideoLayerMode() }
                        if (currentWsBase.isNotBlank()) connectViewerSocket(currentWsBase)
                    }
                }
                mainHandler.postDelayed(this, 800L)
            }
        }
        mainHandler.post(runner)
    }

    private fun refreshVideoLayerMode() {
        if (!viewerEnabled) {
            viewerImage.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            return
        }
        if (viewerHasFreshFrame) {
            viewerImage.visibility = View.VISIBLE
            previewView.visibility = View.GONE
        } else {
            viewerImage.visibility = View.GONE
            previewView.visibility = View.VISIBLE
        }
    }
    private fun updateMapLocation(lat: Double, lng: Double, zoom: Float = 18f) {
        val map = baiduMap ?: return
        val point = LatLng(lat, lng)
        map.isMyLocationEnabled = true
        val locationData = MyLocationData.Builder().latitude(lat).longitude(lng).build()
        map.setMyLocationData(locationData)
        map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(point, zoom))
    }

    private fun toBd09Location(location: Location): LatLng {
        // Android location is usually WGS84; convert to GCJ02 then BD09 for Baidu Map display.
        val (gcjLat, gcjLng) = wgs84ToGcj02(location.latitude, location.longitude)
        return gcj02ToBd09(gcjLat, gcjLng)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * kotlin.math.sin(6.0 * x * kotlin.math.PI) + 20.0 * kotlin.math.sin(2.0 * x * kotlin.math.PI)) * 2.0 / 3.0
        ret += (20.0 * kotlin.math.sin(y * kotlin.math.PI) + 40.0 * kotlin.math.sin(y / 3.0 * kotlin.math.PI)) * 2.0 / 3.0
        ret += (160.0 * kotlin.math.sin(y / 12.0 * kotlin.math.PI) + 320 * kotlin.math.sin(y * kotlin.math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * kotlin.math.sin(6.0 * x * kotlin.math.PI) + 20.0 * kotlin.math.sin(2.0 * x * kotlin.math.PI)) * 2.0 / 3.0
        ret += (20.0 * kotlin.math.sin(x * kotlin.math.PI) + 40.0 * kotlin.math.sin(x / 3.0 * kotlin.math.PI)) * 2.0 / 3.0
        ret += (150.0 * kotlin.math.sin(x / 12.0 * kotlin.math.PI) + 300.0 * kotlin.math.sin(x / 30.0 * kotlin.math.PI)) * 2.0 / 3.0
        return ret
    }

    private fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLon = transformLon(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * kotlin.math.PI
        var magic = kotlin.math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * kotlin.math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * kotlin.math.cos(radLat) * kotlin.math.PI)
        return (lat + dLat) to (lng + dLon)
    }

    private fun gcj02ToBd09(lat: Double, lng: Double): LatLng {
        val z = kotlin.math.sqrt(lng * lng + lat * lat) + 0.00002 * kotlin.math.sin(lat * kotlin.math.PI * 3000.0 / 180.0)
        val theta = kotlin.math.atan2(lat, lng) + 0.000003 * kotlin.math.cos(lng * kotlin.math.PI * 3000.0 / 180.0)
        val bdLng = z * kotlin.math.cos(theta) + 0.0065
        val bdLat = z * kotlin.math.sin(theta) + 0.006
        return LatLng(bdLat, bdLng)
    }

    private fun isBetterLocation(candidate: Location, currentBest: Location?): Boolean {
        if (currentBest == null) return true
        val hasAcc = candidate.hasAccuracy()
        val bestHasAcc = currentBest.hasAccuracy()
        return when {
            hasAcc && !bestHasAcc -> true
            !hasAcc && bestHasAcc -> false
            hasAcc && bestHasAcc -> candidate.accuracy < currentBest.accuracy
            else -> candidate.time > currentBest.time
        }
    }

    private fun stopContinuousLocation() {
        val listener = locatingListener
        if (listener != null) {
            try {
                locationManager.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        locatingListener = null
        locatingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        locatingTimeoutRunnable = null
    }

    private fun finishLocationSelection(location: Location) {
        stopContinuousLocation()
        locatingBestLocation = location
        val bd = toBd09Location(location)
        currentLat = bd.latitude
        currentLng = bd.longitude
        tvMapCurrentLocationValue.text = formatLatLng(bd.latitude, bd.longitude)
        updateMapLocation(bd.latitude, bd.longitude)

        val accText = if (location.hasAccuracy()) String.format(Locale.US, "%.0f", location.accuracy) else "-"
        setMapStatus("状态：定位成功(精度约 ${accText}m)，正在解析地址...")
        val payload = JSONObject().put("lat", bd.latitude).put("lng", bd.longitude).put("coordtype", "bd09ll")
        postJson("/api/map/reverse-geocode", payload) { json, err ->
            if (err != null) {
                setMapStatus("状态：$err")
                return@postJson
            }
            if (json == null) {
                setMapStatus("状态：地址解析失败")
                return@postJson
            }
            if (json.optBoolean("success", false)) {
                val addr = json.optString("formatted_address", "").ifBlank { "定位成功" }
                tvMapCurrentLocationValue.text = addr
                setMapStatus("状态：定位完成")
            } else {
                setMapStatus("状态：${jsonErrorText(json)}")
            }
        }
    }

    private fun locateAndReverseGeocode() {
        if (!hasLocationPermission()) {
            pendingLocateAfterPermission = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION_PERMISSION
            )
            return
        }
        if (!isConnected) {
            setMapStatus("状态：未连接后端，请先在导航页点击 Connect")
            return
        }

        stopContinuousLocation()
        locatingBestLocation = bestLastKnownLocation()
        setMapStatus("状态：正在定位(约3-5秒)...")

        val listener = LocationListener { location ->
            if (isBetterLocation(location, locatingBestLocation)) {
                locatingBestLocation = location
            }
            val best = locatingBestLocation
            if (best != null && best.hasAccuracy() && best.accuracy <= LOCATE_GOOD_ACCURACY_M) {
                finishLocationSelection(best)
            }
        }
        locatingListener = listener

        try {
            val enabledProviders = locationManager.getProviders(true) ?: emptyList()
            if (enabledProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (enabledProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    500L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            stopContinuousLocation()
            setMapStatus("状态：定位权限不足")
            return
        } catch (e: Exception) {
            stopContinuousLocation()
            setMapStatus("状态：定位启动失败: ${e.message}")
            return
        }

        locatingTimeoutRunnable = Runnable {
            val best = locatingBestLocation
            if (best == null) {
                setMapStatus("状态：无法获取定位，请开启系统定位")
                stopContinuousLocation()
                return@Runnable
            }
            if (best.hasAccuracy() && best.accuracy > LOCATE_MAX_FALLBACK_ACCURACY_M) {
                setMapStatus("状态：定位精度较差(${String.format(Locale.US, "%.0f", best.accuracy)}m)，请在开阔区域重试")
                stopContinuousLocation()
                return@Runnable
            }
            finishLocationSelection(best)
        }
        mainHandler.postDelayed(locatingTimeoutRunnable!!, LOCATE_WINDOW_MS)
    }

    private fun geocodeThenRoute() {
        if (!ensureMapReady()) return
        val destination = etMapDestination.text?.toString()?.trim().orEmpty()
        if (destination.isEmpty()) {
            setMapStatus("状态：请输入目的地")
            return
        }
        val lat = currentLat
        val lng = currentLng
        if (lat == null || lng == null) {
            setMapStatus("状态：请先点击“定位我的位置”")
            return
        }
        setMapStatus("状态：正在解析目的地...")
        val geoPayload = JSONObject().put("address", destination)
        postJson("/api/map/geocode", geoPayload) { geoJson, geoErr ->
            if (geoErr != null) {
                setMapStatus("状态：$geoErr")
                return@postJson
            }
            if (geoJson == null || !geoJson.optBoolean("success", false)) {
                setMapStatus("状态：${jsonErrorText(geoJson)}")
                return@postJson
            }
            val destLat = geoJson.optDouble("lat", Double.NaN)
            val destLng = geoJson.optDouble("lng", Double.NaN)
            if (destLat.isNaN() || destLng.isNaN()) {
                setMapStatus("状态：目的地坐标无效")
                return@postJson
            }
            setMapStatus("状态：正在规划步行路线...")
            updateMapLocation(destLat, destLng, 16f)
            val routePayload = JSONObject()
                .put("origin_lat", lat)
                .put("origin_lng", lng)
                .put("dest_lat", destLat)
                .put("dest_lng", destLng)
                .put("mode", "walking")
                .put("coordtype", "bd09ll")
            postJson("/api/map/route", routePayload) { routeJson, routeErr ->
                if (routeErr != null) {
                    setMapStatus("状态：$routeErr")
                    return@postJson
                }
                if (routeJson == null || !routeJson.optBoolean("success", false)) {
                    setMapStatus("状态：${jsonErrorText(routeJson)}")
                    return@postJson
                }
                val distance = routeJson.optDouble("distance", 0.0)
                val durationSec = routeJson.optDouble("duration", 0.0)
                val steps = routeJson.optJSONArray("steps") ?: JSONArray()
                val firstStep = steps.optString(0, "请按路线前进")
                tvMapRouteSummary.text = "路线摘要：总距离${formatDistance(distance)}，预计${formatDuration(durationSec)}\n首条指引：$firstStep"
                setMapStatus("状态：路线规划完成")
            }
        }
    }

    private fun showMorePoiDialog() {
        if (!ensureMapReady()) return
        if (currentLat == null || currentLng == null) {
            setMapStatus("状态：请先点击“定位我的位置”")
            return
        }
        val items = arrayOf("地铁站", "医院", "商场")
        AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(items) { _, which -> searchNearby(items[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun searchNearby(query: String) {
        val lat = currentLat ?: return
        val lng = currentLng ?: return
        setMapStatus("状态：正在搜索附近$query...")
        getJson(
            "/api/map/nearby",
            mapOf("query" to query, "lat" to lat.toString(), "lng" to lng.toString(), "radius" to "1200")
        ) { json, err ->
            if (err != null) {
                setMapStatus("状态：$err")
                return@getJson
            }
            if (json == null || !json.optBoolean("success", false)) {
                setMapStatus("状态：${jsonErrorText(json)}")
                return@getJson
            }
            val places = json.optJSONArray("places") ?: JSONArray()
            if (places.length() == 0) {
                tvMapRouteSummary.text = "路线摘要：附近未找到$query"
                tvMapNearestBlindValue.text = "未知"
                setMapStatus("状态：附近搜索完成")
                return@getJson
            }
            val lines = mutableListOf<String>()
            var nearestDistanceText = "未知"
            for (i in 0 until minOf(3, places.length())) {
                val p = places.optJSONObject(i) ?: continue
                val name = p.optString("name", "未知地点")
                val distanceText = distanceFieldToText(p.opt("distance"))
                if (i == 0) nearestDistanceText = distanceText
                lines.add("${i + 1}. $name（$distanceText）")
            }
            tvMapNearestBlindValue.text = nearestDistanceText
            tvMapRouteSummary.text = "路线摘要：附近$query\n" + lines.joinToString("\n")
            setMapStatus("状态：附近搜索完成")
        }
    }

    private fun ensureMapReady(): Boolean {
        saveEndpoint()
        if (!isConnected) {
            setMapStatus("状态：未连接后端，请先在导航页点击 Connect")
            return false
        }
        return true
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun bestLastKnownLocation(): Location? {
        return try {
            val providers = locationManager.getProviders(true) ?: emptyList()
            var best: Location? = null
            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best.time) best = loc
            }
            best
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            Log.e(TAG, "location read failed", e)
            null
        }
    }

    private fun postJson(path: String, payload: JSONObject, callback: (JSONObject?, String?) -> Unit) {
        val base = currentBaseUrl()
        Thread {
            try {
                val req = Request.Builder().url(base + path).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val json = parseJson(body)
                    if (!resp.isSuccessful) {
                        val msg = jsonErrorText(json, fallback = "HTTP ${resp.code}")
                        runOnUiThread { callback(json, msg) }
                        return@use
                    }
                    runOnUiThread { callback(json, null) }
                }
            } catch (e: Exception) {
                val msg = if (e is SocketTimeoutException) {
                    "请求超时（AI/地图服务响应较慢，请重试）"
                } else {
                    "请求失败: ${e.message}"
                }
                runOnUiThread { callback(null, msg) }
            }
        }.start()
    }

    private fun getJson(path: String, query: Map<String, String>, callback: (JSONObject?, String?) -> Unit) {
        val base = currentBaseUrl()
        Thread {
            try {
                val uriBuilder = Uri.parse(base + path).buildUpon()
                for ((k, v) in query) uriBuilder.appendQueryParameter(k, v)
                val req = Request.Builder().url(uriBuilder.build().toString()).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val json = parseJson(body)
                    if (!resp.isSuccessful) {
                        val msg = jsonErrorText(json, fallback = "HTTP ${resp.code}")
                        runOnUiThread { callback(json, msg) }
                        return@use
                    }
                    runOnUiThread { callback(json, null) }
                }
            } catch (e: Exception) {
                val msg = if (e is SocketTimeoutException) {
                    "请求超时（AI/地图服务响应较慢，请重试）"
                } else {
                    "请求失败: ${e.message}"
                }
                runOnUiThread { callback(null, msg) }
            }
        }.start()
    }

    private fun parseJson(body: String): JSONObject? {
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonErrorText(json: JSONObject?, fallback: String = "请求失败"): String {
        if (json == null) return fallback
        val msg = json.optString("message", "")
        if (msg.isNotBlank()) return msg
        val err = json.optString("error", "")
        if (err.isNotBlank()) return err
        val raw = json.optString("error_detail", "")
        if (raw.isNotBlank()) return raw
        return fallback
    }

    private fun distanceFieldToText(v: Any?): String {
        return when (v) {
            is Number -> formatDistance(v.toDouble())
            is String -> v.ifBlank { "未知" }
            else -> "未知"
        }
    }

    private fun formatDistance(meter: Double): String {
        if (meter <= 0.0) return "未知"
        return if (meter >= 1000.0) String.format(Locale.US, "%.2f km", meter / 1000.0) else String.format(Locale.US, "%.0f m", meter)
    }

    private fun formatDuration(sec: Double): String {
        if (sec <= 0.0) return "未知"
        val minute = sec / 60.0
        return if (minute >= 60.0) {
            val h = (minute / 60.0).toInt()
            val m = (minute % 60.0).toInt()
            "${h}小时${m}分钟"
        } else {
            "${minute.toInt()}分钟"
        }
    }

    private fun formatLatLng(lat: Double, lng: Double): String {
        return String.format(Locale.US, "%.6f, %.6f", lat, lng)
    }

    private fun setMapStatus(text: String) {
        tvMapStatus.text = text
    }

    private fun callNavCommand(path: String) {
        if (!isConnected) {
            Toast.makeText(this, "Please connect first.", Toast.LENGTH_SHORT).show()
            return
        }
        saveEndpoint()
        val baseHttp = currentBaseUrl()
        Thread {
            try {
                val req = Request.Builder()
                    .url(baseHttp + path)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val ok = resp.isSuccessful
                    val body = resp.body?.string().orEmpty()
                    if (!ok) throw IllegalStateException("HTTP ${resp.code}: $body")
                    val obj = JSONObject(body)
                    isNavEnabled = obj.optBoolean("nav_enabled", false)
                    runOnUiThread { updateStatus("Nav command ok: ${obj.optString("state")}") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "nav command failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Nav command failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun initAudioTrack() {
        val minBuffer = AudioTrack.getMinBufferSize(
            8000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            8000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer.coerceAtLeast(2048),
            AudioTrack.MODE_STREAM
        )
        audioTrack?.play()
    }

    private fun updateStatus(text: String) {
        tvStatus.text = "Status: $text"
    }

    private fun currentBaseUrl(): String {
        val hostRaw = etHost.text?.toString()?.trim().orEmpty()
        val portRaw = etPort.text?.toString()?.trim().orEmpty()
        val host = if (hostRaw.isEmpty()) DEFAULT_HOST else hostRaw
        val port = if (portRaw.matches(Regex("\\d+"))) portRaw else DEFAULT_PORT
        val prefixHost = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
        return "$prefixHost:$port"
    }

    private fun saveEndpoint() {
        val host = etHost.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_HOST }
        val port = etPort.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_PORT }
        prefs.edit().putString(KEY_HOST, host).putString(KEY_PORT, port).apply()
    }

    private fun closeSockets() {
        wsCamera?.close(1000, "reconnect")
        wsGuidance?.close(1000, "reconnect")
        wsAudio?.close(1000, "reconnect")
        wsViewer?.close(1000, "reconnect")
        wsAsrProxy?.close(1000, "reconnect")
        wsCamera = null
        wsGuidance = null
        wsAudio = null
        wsViewer = null
        wsAsrProxy = null
    }

    override fun onDestroy() {
        stopContinuousLocation()
        stopProxyAsrRecognition(sendStop = true)
        releaseSparkChain()
        try {
            baiduMap?.isMyLocationEnabled = false
            baiduMapView?.onDestroy()
        } catch (e: Throwable) {
            Log.e(TAG, "map destroy failed", e)
        }
        baiduMapView = null
        baiduMap = null
        super.onDestroy()
        isNavEnabled = false
        isConnected = false
        viewerMonitorStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        closeSockets()
        audioTrack?.stop()
        audioTrack?.release()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        try {
            baiduMapView?.onResume()
        } catch (e: Throwable) {
            Log.e(TAG, "map resume failed", e)
        }
    }

    override fun onPause() {
        stopProxyAsrRecognition(sendStop = true)
        stopSparkAsr(true)
        stopSparkTtsPlayback()
        try {
            baiduMapView?.onPause()
        } catch (e: Throwable) {
            Log.e(TAG, "map pause failed", e)
        }
        super.onPause()
    }
}

















