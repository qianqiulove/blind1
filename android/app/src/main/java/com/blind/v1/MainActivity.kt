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
import com.iflytek.cloud.InitListener
import com.iflytek.cloud.SpeechConstant
import com.iflytek.cloud.SpeechError
import com.iflytek.cloud.SpeechEvent
import com.iflytek.cloud.SpeechUtility
import com.iflytek.cloud.VoiceWakeuper
import com.iflytek.cloud.WakeuperListener
import com.iflytek.cloud.WakeuperResult
import com.iflytek.cloud.util.ResourceUtil
import com.iflytek.cloud.util.ResourceUtil.RESOURCE_TYPE
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
import java.io.File
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
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
    private lateinit var tvJourneyDurationValue: TextView
    private lateinit var llRiskTimeline: LinearLayout
    private lateinit var tvRiskEmpty: TextView

    private lateinit var pageNav: View
    private lateinit var pageMap: View
    private lateinit var pageAssistant: View
    private lateinit var pageSettings: View
    private lateinit var btnTabNav: Button
    private lateinit var btnTabMap: Button
    private lateinit var btnTabAssistant: Button
    private lateinit var btnTabSettings: Button
    private lateinit var btnLogout: Button
    private lateinit var btnLocateMe: Button
    private lateinit var etMapDestination: EditText
    private lateinit var btnMapSearch: Button
    private lateinit var btnMapMore: Button
    private lateinit var btnToggleDebug: Button
    private lateinit var debugControlsContainer: View
    private lateinit var tvMapStatus: TextView
    private lateinit var tvMapCurrentLocationValue: TextView
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
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    private var wsCamera: WebSocket? = null
    private var wsGuidance: WebSocket? = null
    private var wsAudio: WebSocket? = null
    private var wsViewer: WebSocket? = null
    private var wsAsrProxy: WebSocket? = null
    private var wsCameraConnected = false
    private var wsGuidanceConnected = false
    private var wsAudioConnected = false
    private var wsViewerConnected = false

    private var isConnected = false
    private var isNavEnabled = false
    private var lastGuidanceText: String = ""
    private var currentWsBase: String = ""
    private var currentTab: Int = TAB_NAV
    private var debugControlsVisible: Boolean = false
    private var pendingAutoStartNav: Boolean = false
    private var navRunning: Boolean = false
    private var logoutInProgress: Boolean = false
    private var navSessionStartedAtMs: Long = 0L
    private val riskEvents = ArrayDeque<RiskTimelineItem>()
    private var lastRiskGuidanceText: String = ""
    private var lastRiskAddedAtMs: Long = 0L
    private val riskTimeFormatter = SimpleDateFormat("HH:mm", Locale.CHINA)

    private var viewerEnabled: Boolean = false
    private var viewerHasFreshFrame: Boolean = false
    private var lastViewerFrameAtMs: Long = 0L
    private var lastViewerHeartbeatAtMs: Long = 0L
    private var viewerReconnectScheduled = false
    private var viewerMonitorStarted = false
    private var coreReconnectScheduled = false
    private var coreReconnectAttempt = 0
    private var coreReconnectRunnable: Runnable? = null
    private var suppressReconnectUntilMs = 0L
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
    private var wakeuper: VoiceWakeuper? = null
    private var wakeSdkReady: Boolean = false
    private var wakeListening: Boolean = false
    private var wakeInitRequested: Boolean = false
    private var wakeDiagStatus: String = "未启用"
    private var wakeAuthEvent: String = "none"
    private var wakeLastHitAtMs: Long = 0L
    private var appInForeground: Boolean = false
    private var proxyAsrRecorder: AudioRecord? = null
    private var proxyAsrThread: Thread? = null
    private val proxyAsrWriteEnabled = AtomicBoolean(false)
    private var proxyAsrBestText: String = ""
    private var sparkTts: OnlineTTS? = null
    private var sparkTtsTrack: AudioTrack? = null
    private var sparkTtsPlaying: Boolean = false
    private var isAssistantSpeaking: Boolean = false
    private var lastNavAudioAtMs: Long = 0L
    private var assistantSuppressedUntilMs: Long = 0L
    private var pendingAssistantTtsText: String = ""
    private var assistantResumeRunnable: Runnable? = null
    private var assistantTtsEnabled: Boolean = true
    private val assistantHistory = JSONArray()
    @Volatile
    private var authRedirecting: Boolean = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    companion object {
        private const val TAG = "blind-v1"
        private const val TAG_VOICE = "blind-v1-voice"
        private const val PREFS_NAME = "blind_v1_settings"
        private const val KEY_HOST = "backend_host"
        private const val KEY_PORT = "backend_port"
        private const val KEY_ASSISTANT_TTS_ENABLED = "assistant_tts_enabled"
        private const val KEY_MOBILE_TOKEN = "mobile_token"
        private const val KEY_MOBILE_EXPIRES_AT = "mobile_token_expires_at"
        private const val KEY_MOBILE_USERNAME = "mobile_username"
        private const val DEFAULT_HOST = "10.0.2.2"
        private const val DEFAULT_PORT = "8088"

        private const val REQ_CAMERA_PERMISSION = 1001
        private const val REQ_LOCATION_PERMISSION = 1002
        private const val REQ_AUDIO_PERMISSION = 1003

        private const val TAB_NAV = 0
        private const val TAB_MAP = 1
        private const val TAB_ASSISTANT = 2
        private const val TAB_SETTINGS = 3

        private const val FRAME_SEND_INTERVAL_MS = 70L
        private const val CAMERA_JPEG_QUALITY = 35
        private const val FRAME_SEND_INTERVAL_CONGESTED_MS = 120L
        private const val CAMERA_JPEG_QUALITY_CONGESTED = 26
        private const val CAMERA_WS_QUEUE_WARN_BYTES = 128 * 1024L
        private const val CAMERA_WS_QUEUE_HARD_DROP_BYTES = 512 * 1024L

        private const val VIEWER_STALE_TIMEOUT_MS = 2200L
        private const val VIEWER_RECONNECT_DELAY_MS = 1200L
        private const val VIEWER_HEARTBEAT_INTERVAL_MS = 10_000L
        private const val CORE_WS_RECONNECT_BASE_MS = 1200L
        private const val CORE_WS_RECONNECT_MAX_MS = 8000L
        private const val CORE_WS_RECONNECT_SUPPRESS_MS = 1800L
        private const val LOCATE_WINDOW_MS = 5000L
        private const val LOCATE_GOOD_ACCURACY_M = 30f
        private const val LOCATE_MAX_FALLBACK_ACCURACY_M = 80f

        private const val SPARK_TTS_SAMPLE_RATE = 16000
        private const val SPARK_ASR_SAMPLE_RATE = 16000
        private const val SPARK_ASR_RESULT_TIMEOUT_MS = 15000L
        private const val SPARK_ASR_RETRY_DELAY_MS = 700L
        private const val SPARK_ASR_MAX_RETRY = 1
        private const val WAKE_HIT_COOLDOWN_MS = 2800L
        private const val NAV_AUDIO_SUPPRESS_MS = 1800L
        private const val ASSISTANT_RESUME_MIN_DELAY_MS = 250L
        private const val RISK_EVENT_MAX_COUNT = 40
        private const val RISK_EVENT_DEDUPE_WINDOW_MS = 3000L
    }

    private data class RiskTimelineItem(
        val type: String,
        val timeLabel: String,
        val guidance: String,
        val tag: String
    )

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
        if (!ensureLoggedInOrRedirect()) return
        try {
            setContentView(R.layout.activity_main)
            bindViews()
            initTabs()
            initNavPageActions()
            initMapPageActions()
            initAssistantPageActions()
            initSettingsPageActions()
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
        tvJourneyDurationValue = findViewById(R.id.tvJourneyDurationValue)
        llRiskTimeline = findViewById(R.id.llRiskTimeline)
        tvRiskEmpty = findViewById(R.id.tvRiskEmpty)

        pageNav = findViewById(R.id.pageNav)
        pageMap = findViewById(R.id.pageMap)
        pageAssistant = findViewById(R.id.pageAssistant)
        pageSettings = findViewById(R.id.pageSettings)
        btnTabNav = findViewById(R.id.btnTabNav)
        btnTabMap = findViewById(R.id.btnTabMap)
        btnTabAssistant = findViewById(R.id.btnTabAssistant)
        btnTabSettings = findViewById(R.id.btnTabSettings)
        btnLogout = findViewById(R.id.btnLogout)
        btnLocateMe = findViewById(R.id.btnLocateMe)
        etMapDestination = findViewById(R.id.etMapDestination)
        btnMapSearch = findViewById(R.id.btnMapSearch)
        btnMapMore = findViewById(R.id.btnMapMore)
        btnToggleDebug = findViewById(R.id.btnToggleDebug)
        debugControlsContainer = findViewById(R.id.debugControlsContainer)
        tvMapStatus = findViewById(R.id.tvMapStatus)
        tvMapCurrentLocationValue = findViewById(R.id.tvMapCurrentLocationValue)
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
        wakeDiagStatus = "已暂时移除"
        updateVoiceDiagnostics(
            permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            sparkSdkReady = sparkReady,
            sparkAsrActive = sparkAsrListening,
            detail = "初始化完成（App端语音唤醒已暂时移除）"
        )
        refreshDebugControlsUi()
        renderRiskTimeline()
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
        btnTabSettings.setOnClickListener { switchTab(TAB_SETTINGS) }
        switchTab(TAB_NAV)
        setMapStatus("状态：等待定位...")
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        pageMap.visibility = if (tab == TAB_MAP) View.VISIBLE else View.GONE
        pageNav.visibility = if (tab == TAB_NAV) View.VISIBLE else View.GONE
        pageAssistant.visibility = if (tab == TAB_ASSISTANT) View.VISIBLE else View.GONE
        pageSettings.visibility = if (tab == TAB_SETTINGS) View.VISIBLE else View.GONE
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
        btnTabSettings.backgroundTintList = if (tab == TAB_SETTINGS) activeBg else inactiveBg
        btnTabNav.setTextColor(if (tab == TAB_NAV) activeText else inactiveText)
        btnTabMap.setTextColor(if (tab == TAB_MAP) activeText else inactiveText)
        btnTabAssistant.setTextColor(if (tab == TAB_ASSISTANT) activeText else inactiveText)
        btnTabSettings.setTextColor(if (tab == TAB_SETTINGS) activeText else inactiveText)
    }

    private fun initNavPageActions() {
        findViewById<Button>(R.id.btnConnect).setOnClickListener { startUserNavigation() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopUserNavigation() }
        findViewById<Button>(R.id.btnSosPlaceholder).setOnClickListener {
            Toast.makeText(this, "SOS 通道将在后续版本接入。", Toast.LENGTH_SHORT).show()
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
                assistantResumeRunnable?.let { mainHandler.removeCallbacks(it) }
                assistantResumeRunnable = null
                pendingAssistantTtsText = ""
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

    private fun initSettingsPageActions() {
        btnToggleDebug.setOnClickListener {
            debugControlsVisible = !debugControlsVisible
            refreshDebugControlsUi()
        }
        findViewById<Button>(R.id.btnTrafficTest).setOnClickListener {
            callNavCommand("/api/traffic-test/start")
        }
        findViewById<Button>(R.id.btnComprehensiveTest).setOnClickListener {
            callNavCommand("/api/nav/start?mode=blind_nav")
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
                wsViewer?.close(1000, "overlay_off")
                wsViewer = null
                wsViewerConnected = false
                lastViewerHeartbeatAtMs = 0L
                viewerHasFreshFrame = false
                refreshVideoLayerMode()
                viewerImage.setImageBitmap(null)
            }
        }
        btnLogout.setOnClickListener { performLogout() }
    }

    private fun performLogout() {
        if (logoutInProgress) return
        logoutInProgress = true
        btnLogout.isEnabled = false
        btnLogout.text = getString(R.string.settings_logout_processing)
        authRedirecting = true

        val tokenSnapshot = currentMobileToken()
        if (tokenSnapshot.isNotBlank()) {
            Thread {
                try {
                    val payload = JSONObject()
                    val req = Request.Builder()
                        .url(currentBaseUrl() + "/api/mobile/auth/logout")
                        .header("Authorization", "Bearer $tokenSnapshot")
                        .header("X-Mobile-Token", tokenSnapshot)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { _ -> }
                } catch (e: Exception) {
                    Log.w(TAG, "logout api failed: ${e.message}")
                }
            }.start()
        }

        try {
            closeSockets()
        } catch (_: Exception) {
        }
        prefs.edit()
            .remove(KEY_MOBILE_TOKEN)
            .remove(KEY_MOBILE_EXPIRES_AT)
            .remove(KEY_MOBILE_USERNAME)
            .apply()
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
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
                    sparkSdkReady = sparkReady,
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
                    sparkSdkReady = sparkReady,
                    sparkAsrActive = false,
                    detail = "录音权限未开启"
                )
                Toast.makeText(this, "语音权限未授予。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshDebugControlsUi() {
        debugControlsContainer.visibility = if (debugControlsVisible) View.VISIBLE else View.GONE
        btnToggleDebug.text = if (debugControlsVisible) "收起调试" else "调试"
    }

    private fun startVoiceInput() {
        val permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (permissionOk) {
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "开始语音识别（后端代理）"
            )
            startProxyAsrRecognition(resetRetry = true)
        } else {
            updateVoiceDiagnostics(
                permissionOk = false,
                sparkSdkReady = sparkReady,
                sparkAsrActive = false,
                detail = "请求录音权限"
            )
            pendingSpeechAfterPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION)
        }
    }

    private fun canPlayAssistantTtsNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (currentTab != TAB_ASSISTANT) return false
        if (!assistantTtsEnabled) return false
        if (!sparkReady) return false
        if (sparkAsrListening) return false
        if (nowMs < assistantSuppressedUntilMs) return false
        return (nowMs - lastNavAudioAtMs) >= NAV_AUDIO_SUPPRESS_MS
    }

    private fun assistantSuppressDetail(nowMs: Long = System.currentTimeMillis()): String {
        return when {
            sparkAsrListening -> "语音识别中，助手朗读已延后"
            nowMs < assistantSuppressedUntilMs -> "导航播报中，助手朗读已延后"
            else -> "助手朗读暂时不可用，已延后重试"
        }
    }

    private fun schedulePendingAssistantTtsResume(trigger: String) {
        val pending = pendingAssistantTtsText.trim()
        if (pending.isEmpty()) return
        assistantResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        val now = System.currentTimeMillis()
        val delay = (assistantSuppressedUntilMs - now).coerceAtLeast(ASSISTANT_RESUME_MIN_DELAY_MS)
        val runnable = Runnable {
            assistantResumeRunnable = null
            val text = pendingAssistantTtsText.trim()
            if (text.isEmpty()) return@Runnable
            if (currentTab != TAB_ASSISTANT || !assistantTtsEnabled || !sparkReady) {
                pendingAssistantTtsText = ""
                return@Runnable
            }
            if (sparkAsrListening || !canPlayAssistantTtsNow()) {
                schedulePendingAssistantTtsResume(trigger = "blocked")
                return@Runnable
            }
            pendingAssistantTtsText = ""
            Log.i(TAG_VOICE, "ASST_RESUME trigger=$trigger")
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "导航播报结束，恢复助手朗读"
            )
            startAssistantTtsNow(text)
        }
        assistantResumeRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
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
        val req = applyAuthHeaders(Request.Builder().url("$wsBase/ws/asr_proxy")).build()
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
                if (response?.code == 401) {
                    onAuthExpired("WS asr_proxy")
                    return
                }
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
        schedulePendingAssistantTtsResume(trigger = "asr_stop")
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
        schedulePendingAssistantTtsResume(trigger = "spark_asr_stop")
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

    private val wakeInitListener = InitListener { code ->
        runOnUiThread {
            wakeAuthEvent = "AUTH:$code"
            if (code == 0) {
                wakeSdkReady = true
                wakeDiagStatus = "初始化成功"
                Log.i(TAG_VOICE, "WAKE_INIT_OK type=AUTH code=$code")
                updateVoiceDiagnostics(
                    permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    sparkSdkReady = sparkReady,
                    sparkAsrActive = sparkAsrListening,
                    detail = "MSC唤醒初始化成功"
                )
                resumeWakeListeningIfNeeded("wake_auth_ok")
            } else {
                wakeSdkReady = false
                wakeDiagStatus = "初始化失败(code=$code)"
                Log.e(TAG_VOICE, "WAKE_INIT_FAIL type=AUTH code=$code")
                updateVoiceDiagnostics(
                    permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    sparkSdkReady = sparkReady,
                    sparkAsrActive = sparkAsrListening,
                    detail = "MSC唤醒初始化失败，错误码=$code"
                )
            }
        }
    }

    private val wakeuperListener = object : WakeuperListener {
        override fun onResult(result: WakeuperResult?) {
            val text = result?.resultString.orEmpty()
            onWakeupHit("msc_wakeup", text)
        }

        override fun onError(error: SpeechError?) {
            val code = error?.errorCode ?: -1
            val desc = error?.getPlainDescription(true).orEmpty()
            wakeDiagStatus = "识别异常(code=$code)"
            wakeAuthEvent = "WAKE_ERR:$code"
            Log.e(TAG_VOICE, "WAKE_ERROR code=$code msg=$desc")
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒识别异常：code=$code, msg=${if (desc.isBlank()) "unknown" else desc}"
            )
            wakeListening = false
        }

        override fun onBeginOfSpeech() {
            // no-op
        }

        override fun onEvent(eventType: Int, isLast: Int, arg2: Int, obj: Bundle?) {
            if (eventType == SpeechEvent.EVENT_RECORD_DATA) {
                Log.i(TAG_VOICE, "WAKE_RECORD_DATA")
            }
        }

        override fun onVolumeChanged(volume: Int) {
            // no-op
        }
    }

    private fun wakeAbilityId(): String {
        return BuildConfig.IFLYTEK_WAKE_ABILITY_ID.trim().ifBlank { "e867a88f2" }
    }

    private fun wakeThreshold(): String {
        return BuildConfig.IFLYTEK_WAKE_THRESHOLD.trim().ifBlank { "0 0:800" }
    }

    private fun wakeThresholdValue(): Int {
        val raw = wakeThreshold()
        val lastNum = Regex("(\\d+)").findAll(raw).lastOrNull()?.value?.toIntOrNull()
        return (lastNum ?: 1450).coerceIn(0, 3000)
    }

    private fun wakeResourcePath(): String {
        val appId = BuildConfig.IFLYTEK_APP_ID.trim()
        return ResourceUtil.generateResourcePath(
            this,
            RESOURCE_TYPE.assets,
            "ivw/$appId.jet"
        )
    }

    private fun initWakeupEngine() {
        if (!BuildConfig.IFLYTEK_WAKE_ENABLE) {
            wakeDiagStatus = "未启用（IFLYTEK_WAKE_ENABLE=false）"
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒词未启用"
            )
            return
        }
        if (BuildConfig.IFLYTEK_APP_ID.isBlank()) {
            wakeSdkReady = false
            wakeDiagStatus = "未配置密钥"
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒词不可用：缺少 IFLYTEK_APP_ID"
            )
            return
        }
        if (!ensureWakeResourceFile()) {
            wakeSdkReady = false
            wakeDiagStatus = "资源缺失"
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒资源加载失败，请检查 assets/ivw/${BuildConfig.IFLYTEK_APP_ID}.jet"
            )
            return
        }
        try {
            val param = "appid=${BuildConfig.IFLYTEK_APP_ID},${SpeechConstant.ENGINE_MODE}=${SpeechConstant.MODE_MSC}"
            SpeechUtility.createUtility(applicationContext, param)
            val ivw = VoiceWakeuper.createWakeuper(this, wakeInitListener)
            wakeuper = ivw
            wakeInitRequested = true
            if (ivw != null) {
                // 部分 MSC 版本不会触发 InitListener，createWakeuper 非空即视为可用。
                wakeSdkReady = true
                wakeDiagStatus = "初始化成功"
                if (wakeAuthEvent == "none") wakeAuthEvent = "INIT:0"
                updateVoiceDiagnostics(
                    permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    sparkSdkReady = sparkReady,
                    sparkAsrActive = sparkAsrListening,
                    detail = "MSC唤醒初始化成功"
                )
                resumeWakeListeningIfNeeded("wake_create_ok")
            } else {
                wakeDiagStatus = "初始化中"
                updateVoiceDiagnostics(
                    permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    sparkSdkReady = sparkReady,
                    sparkAsrActive = sparkAsrListening,
                    detail = "MSC唤醒初始化中"
                )
                mainHandler.postDelayed({
                    if (!wakeSdkReady && wakeDiagStatus == "初始化中") {
                        wakeDiagStatus = "初始化超时"
                        wakeAuthEvent = "INIT_TIMEOUT"
                        updateVoiceDiagnostics(
                            permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                            sparkSdkReady = sparkReady,
                            sparkAsrActive = sparkAsrListening,
                            detail = "MSC唤醒初始化超时，请重启应用重试"
                        )
                    }
                }, 3000L)
            }
        } catch (e: Throwable) {
            wakeSdkReady = false
            wakeDiagStatus = "初始化异常(${e.javaClass.simpleName})"
            Log.e(TAG_VOICE, "WAKE_INIT_FAIL setup exception", e)
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒SDK初始化异常：${e.javaClass.simpleName}"
            )
        }
    }

    private fun ensureWakeResourceFile(): Boolean {
        return try {
            val ivwDir = File(filesDir, "ivw")
            if (!ivwDir.exists()) ivwDir.mkdirs()
            val appId = BuildConfig.IFLYTEK_APP_ID.trim()
            val outFile = File(ivwDir, "$appId.jet")
            assets.open("ivw/$appId.jet").use { input ->
                outFile.outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG_VOICE, "copy wake resource failed", e)
            false
        }
    }

    private fun onWakeupHit(key: String, payload: String) {
        val now = System.currentTimeMillis()
        if (now - wakeLastHitAtMs < WAKE_HIT_COOLDOWN_MS) {
            Log.i(TAG_VOICE, "WAKE_COOLDOWN key=$key")
            wakeDiagStatus = "命中冷却中"
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒词命中但处于冷却窗口"
            )
            return
        }
        wakeLastHitAtMs = now
        if (sparkAsrListening) {
            Log.i(TAG_VOICE, "WAKE_BUSY asr_active=true")
            wakeDiagStatus = "命中后等待识别空闲"
            return
        }
        runOnUiThread {
            Log.i(TAG_VOICE, "WAKE_HIT key=$key payload=${payload.take(80)}")
            wakeDiagStatus = "命中成功"
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒词命中，开始语音识别"
            )
            stopWakeListening(reason = "wake_hit")
            startVoiceInput()
        }
    }

    private fun startWakeListening() {
        if (!wakeSdkReady || wakeListening || !appInForeground) return
        if (sparkAsrListening) return
        val permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!permissionOk) {
            wakeDiagStatus = "权限未开启"
            updateVoiceDiagnostics(
                permissionOk = false,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒监听未启动：录音权限未授予"
            )
            return
        }
        val mIvw = VoiceWakeuper.getWakeuper() ?: wakeuper
        if (mIvw == null) {
            wakeDiagStatus = "唤醒对象为空"
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒监听未启动：VoiceWakeuper 为空"
            )
            return
        }
        val appId = BuildConfig.IFLYTEK_APP_ID.trim()
        val assetName = "ivw/$appId.jet"
        val hasAsset = try {
            assets.open(assetName).close()
            true
        } catch (_: Throwable) {
            false
        }
        if (!hasAsset) {
            wakeDiagStatus = "资源缺失"
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒监听未启动：找不到 $assetName"
            )
            return
        }
        try {
            mIvw.setParameter(SpeechConstant.PARAMS, null)
            mIvw.setParameter(SpeechConstant.IVW_THRESHOLD, "0:${wakeThresholdValue()}")
            mIvw.setParameter(SpeechConstant.IVW_SST, "wakeup")
            mIvw.setParameter(SpeechConstant.KEEP_ALIVE, "1")
            mIvw.setParameter(SpeechConstant.IVW_NET_MODE, "0")
            mIvw.setParameter(SpeechConstant.IVW_RES_PATH, wakeResourcePath())
            mIvw.setParameter(SpeechConstant.IVW_AUDIO_PATH, "${getExternalFilesDir("msc")?.absolutePath ?: filesDir.absolutePath}/ivw.wav")
            mIvw.setParameter(SpeechConstant.AUDIO_FORMAT, "wav")
            val ret = mIvw.startListening(wakeuperListener)
            if (ret != 0) {
                wakeListening = false
                wakeDiagStatus = "监听启动失败($ret)"
                wakeAuthEvent = "START:$ret"
                updateVoiceDiagnostics(
                    permissionOk = true,
                    sparkSdkReady = sparkReady,
                    sparkAsrActive = sparkAsrListening,
                    detail = "唤醒监听未启动：startListening=$ret"
                )
                return
            }
            wakeListening = true
            wakeDiagStatus = "监听中"
            wakeAuthEvent = "LISTENING"
            Log.i(TAG_VOICE, "WAKE_START msc threshold=${wakeThresholdValue()}")
            updateVoiceDiagnostics(
                permissionOk = true,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒词监听中"
            )
        } catch (e: Throwable) {
            wakeListening = false
            wakeDiagStatus = "监听启动异常(${e.javaClass.simpleName})"
            Log.e(TAG_VOICE, "WAKE_START failed", e)
            updateVoiceDiagnostics(
                permissionOk = permissionOk,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = "唤醒监听启动异常：${e.javaClass.simpleName}"
            )
        }
    }

    private fun stopWakeListening(reason: String) {
        if (!wakeListening && wakeuper == null) return
        wakeListening = false
        try {
            VoiceWakeuper.getWakeuper()?.stopListening()
        } catch (_: Throwable) {
        }
        wakeDiagStatus = "已停止"
        Log.i(TAG_VOICE, "WAKE_STOP reason=$reason")
    }

    private fun resumeWakeListeningIfNeeded(reason: String) {
        if (!BuildConfig.IFLYTEK_WAKE_ENABLE) return
        if (!appInForeground) return
        if (!wakeInitRequested || !wakeSdkReady) return
        if (sparkAsrListening) return
        if (wakeListening) return
        Log.i(TAG_VOICE, "WAKE_RESUME reason=$reason")
        startWakeListening()
    }

    private fun releaseWakeupEngine() {
        stopWakeListening(reason = "release")
        if (wakeInitRequested) {
            try {
                VoiceWakeuper.getWakeuper()?.destroy()
            } catch (e: Throwable) {
                Log.w(TAG_VOICE, "wake destroy failed", e)
            }
        }
        wakeInitRequested = false
        wakeuper = null
        wakeSdkReady = false
    }

    private fun updateVoiceDiagnostics(
        permissionOk: Boolean,
        sparkSdkReady: Boolean,
        sparkAsrActive: Boolean,
        detail: String
    ) {
        val status = "语音诊断：permission_ok=$permissionOk, spark_sdk_ready=$sparkSdkReady, spark_asr_active=$sparkAsrActive, wake_state=$wakeDiagStatus"
        val runtimeConfig = "运行配置：app_id=${BuildConfig.IFLYTEK_APP_ID}, wake_removed=true"
        val deviceInfo = "设备信息：abi=${Build.SUPPORTED_ABIS?.joinToString("|") ?: "unknown"}"
        runOnUiThread {
            tvAssistantVoiceDiag.text = "$status\n$runtimeConfig\n$deviceInfo\n详情：$detail"
        }
        Log.i(TAG_VOICE, "$status | $runtimeConfig | $deviceInfo | detail=$detail")
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
        val content = text.trim()
        if (content.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!canPlayAssistantTtsNow(now)) {
            pendingAssistantTtsText = content
            val detail = assistantSuppressDetail(now)
            Log.i(TAG_VOICE, "ASST_SUPPRESSED detail=$detail")
            updateVoiceDiagnostics(
                permissionOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                sparkSdkReady = sparkReady,
                sparkAsrActive = sparkAsrListening,
                detail = detail
            )
            schedulePendingAssistantTtsResume(trigger = "suppressed")
            return
        }
        pendingAssistantTtsText = ""
        startAssistantTtsNow(content)
    }

    private fun startAssistantTtsNow(content: String) {
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
                isAssistantSpeaking = true
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
        isAssistantSpeaking = false
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

    private fun startUserNavigation() {
        if (navRunning) {
            Toast.makeText(this, "导航已在运行中。", Toast.LENGTH_SHORT).show()
            return
        }
        val host = normalizedHostInput()
        if (!ensureHostUsableForDevice(host)) return
        etHost.setText(host)
        pendingAutoStartNav = true
        navSessionStartedAtMs = System.currentTimeMillis()
        riskEvents.clear()
        lastRiskGuidanceText = ""
        lastRiskAddedAtMs = 0L
        renderRiskTimeline()
        clearGuidanceDisplay()
        setUserStatusConnecting()
        updateJourneySummary()
        connectSockets()
    }

    private fun stopUserNavigation() {
        pendingAutoStartNav = false
        navRunning = false
        if (isConnected) {
            callNavCommand("/api/nav/stop")
        }
        clearGuidanceDisplay()
        closeSockets()
        lastNavAudioAtMs = 0L
        assistantSuppressedUntilMs = 0L
        schedulePendingAssistantTtsResume(trigger = "nav_stopped")
        setUserStatusStopped()
    }

    private fun maybeAutoStartNav() {
        if (!pendingAutoStartNav) return
        if (!isConnected) return
        pendingAutoStartNav = false
        callNavCommand("/api/nav/start?mode=blind_nav")
    }

    private fun refreshCoreConnectionState() {
        val ready = wsCameraConnected && wsGuidanceConnected && wsAudioConnected
        isConnected = ready
        if (ready) {
            coreReconnectAttempt = 0
            coreReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            coreReconnectRunnable = null
            coreReconnectScheduled = false
            runOnUiThread { maybeAutoStartNav() }
        }
    }

    private fun scheduleCoreReconnect(reason: String) {
        if (!pendingAutoStartNav) return
        if (logoutInProgress || authRedirecting) return
        if (coreReconnectScheduled) return
        val now = System.currentTimeMillis()
        if (now < suppressReconnectUntilMs) return

        coreReconnectScheduled = true
        val exp = if (coreReconnectAttempt >= 4) 4 else coreReconnectAttempt
        val delay = (CORE_WS_RECONNECT_BASE_MS * (1L shl exp)).coerceAtMost(CORE_WS_RECONNECT_MAX_MS)
        Log.w(TAG, "core ws reconnect scheduled in ${delay}ms reason=$reason attempt=$coreReconnectAttempt")
        runOnUiThread { setUserStatusNetworkError("网络波动，${delay / 1000.0}秒后自动重连") }
        val runnable = Runnable {
            coreReconnectScheduled = false
            coreReconnectRunnable = null
            if (!pendingAutoStartNav || logoutInProgress || authRedirecting) return@Runnable
            coreReconnectAttempt += 1
            connectSockets()
        }
        coreReconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun connectSockets() {
        saveEndpoint()
        closeSockets()
        isConnected = false
        wsCameraConnected = false
        wsGuidanceConnected = false
        wsAudioConnected = false
        wsViewerConnected = false
        lastGuidanceLatencyMs = -1L
        lastGuidanceFrameId = 0L
        lastCameraQueueBytes = 0L
        droppedByQueue = 0L
        sentFrames = 0L
        val baseHttp = currentBaseUrl()
        val baseWs = baseHttp.replace("http://", "ws://").replace("https://", "wss://")
        currentWsBase = baseWs

        wsCamera = httpClient.newWebSocket(
            applyAuthHeaders(Request.Builder().url("$baseWs/ws/camera")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsCameraConnected = true
                    refreshCoreConnectionState()
                    runOnUiThread {
                        setUserStatusConnecting()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    wsCameraConnected = false
                    refreshCoreConnectionState()
                    scheduleCoreReconnect("camera_closed:$code")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) {
                        onAuthExpired("WS camera")
                        return
                    }
                    Log.e(TAG, "camera ws failed", t)
                    wsCameraConnected = false
                    refreshCoreConnectionState()
                    navRunning = false
                    runOnUiThread {
                        clearGuidanceDisplay()
                        setUserStatusNetworkError("相机连接失败")
                    }
                    scheduleCoreReconnect("camera_failure")
                }
            }
        )

        wsGuidance = httpClient.newWebSocket(
            applyAuthHeaders(Request.Builder().url("$baseWs/ws/guidance")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsGuidanceConnected = true
                    refreshCoreConnectionState()
                    runOnUiThread {
                        setUserStatusConnecting()
                    }
                    webSocket.send("hello")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    wsGuidanceConnected = false
                    refreshCoreConnectionState()
                    scheduleCoreReconnect("guidance_closed:$code")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = JSONObject(text)
                        val guidance = obj.optString("guidance_text", "").trim()
                        val state = obj.optString("state", "unknown")
                        val traffic = obj.optString("traffic_light", "unknown")
                        val frameId = obj.optLong("frame_id", 0L)
                        val tsSec = obj.optDouble("timestamp", 0.0)
                        val eventTimeMs = if (tsSec > 0.0) (tsSec * 1000.0).toLong() else System.currentTimeMillis()
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
                            tvGuidance.text = if (lastGuidanceText.isBlank()) getString(R.string.nav_guidance_idle) else "播报：$lastGuidanceText"
                            appendRiskTimelineIfNeeded(guidance, traffic, state, eventTimeMs)
                            if (state == "BLIND_NAV" || state == "TRAFFIC_TEST") {
                                navRunning = true
                                setUserStatusRunning(traffic)
                            } else if (state == "IDLE") {
                                navRunning = false
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "guidance json parse failed", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) {
                        onAuthExpired("WS guidance")
                        return
                    }
                    Log.e(TAG, "guidance ws failed", t)
                    wsGuidanceConnected = false
                    refreshCoreConnectionState()
                    navRunning = false
                    runOnUiThread {
                        clearGuidanceDisplay()
                        setUserStatusNetworkError("引导连接失败")
                    }
                    scheduleCoreReconnect("guidance_failure")
                }
            }
        )

        wsAudio = httpClient.newWebSocket(
            applyAuthHeaders(Request.Builder().url("$baseWs/ws/audio")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsAudioConnected = true
                    refreshCoreConnectionState()
                    runOnUiThread {
                        setUserStatusConnecting()
                    }
                    webSocket.send("hello")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    wsAudioConnected = false
                    refreshCoreConnectionState()
                    scheduleCoreReconnect("audio_closed:$code")
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    val now = System.currentTimeMillis()
                    lastNavAudioAtMs = now
                    assistantSuppressedUntilMs = now + NAV_AUDIO_SUPPRESS_MS
                    if (isAssistantSpeaking) {
                        Log.i(TAG_VOICE, "NAV_PREEMPT assistant_tts")
                        stopSparkTtsPlayback()
                        updateVoiceDiagnostics(
                            permissionOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                            sparkSdkReady = sparkReady,
                            sparkAsrActive = sparkAsrListening,
                            detail = "导航播报中，助手朗读已延后"
                        )
                    }
                    audioTrack?.write(bytes.toByteArray(), 0, bytes.size)
                    schedulePendingAssistantTtsResume(trigger = "nav_audio")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) {
                        onAuthExpired("WS audio")
                        return
                    }
                    Log.e(TAG, "audio ws failed", t)
                    wsAudioConnected = false
                    refreshCoreConnectionState()
                    navRunning = false
                    runOnUiThread { setUserStatusNetworkError("音频连接失败") }
                    scheduleCoreReconnect("audio_failure")
                }
            }
        )

        if (viewerEnabled) {
            connectViewerSocket(baseWs)
            monitorViewerStall()
        }
        setUserStatusConnecting()
    }

    private fun connectViewerSocket(baseWs: String) {
        wsViewer?.close(1000, "reconnect")
        wsViewer = httpClient.newWebSocket(
            applyAuthHeaders(Request.Builder().url("$baseWs/ws/viewer")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsViewerConnected = true
                    viewerReconnectScheduled = false
                    lastViewerHeartbeatAtMs = System.currentTimeMillis()
                    runOnUiThread {
                        setUserStatusConnecting()
                    }
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
                    wsViewerConnected = false
                    scheduleViewerReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) {
                        onAuthExpired("WS viewer")
                        return
                    }
                    Log.e(TAG, "viewer ws failed", t)
                    wsViewer = null
                    wsViewerConnected = false
                    navRunning = false
                    runOnUiThread { setUserStatusNetworkError("预览连接失败") }
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
                    if (wsViewerConnected && now - lastViewerHeartbeatAtMs >= VIEWER_HEARTBEAT_INTERVAL_MS) {
                        try {
                            if (wsViewer?.send("ping") == true) {
                                lastViewerHeartbeatAtMs = now
                            }
                        } catch (_: Exception) {
                        }
                    }
                    if (lastViewerFrameAtMs > 0L && (now - lastViewerFrameAtMs) > VIEWER_STALE_TIMEOUT_MS) {
                        viewerHasFreshFrame = false
                        runOnUiThread { refreshVideoLayerMode() }
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
            setMapStatus("状态：未连接后端，请先在导航页点击一键开始导航")
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
                setMapStatus("状态：附近搜索完成")
                return@getJson
            }
            val lines = mutableListOf<String>()
            for (i in 0 until minOf(3, places.length())) {
                val p = places.optJSONObject(i) ?: continue
                val name = p.optString("name", "未知地点")
                val distanceText = distanceFieldToText(p.opt("distance"))
                if (distanceText == "未知") {
                    lines.add("${i + 1}. $name")
                } else {
                    lines.add("${i + 1}. $name（$distanceText）")
                }
            }
            tvMapRouteSummary.text = "路线摘要：附近$query\n" + lines.joinToString("\n")
            setMapStatus("状态：附近搜索完成")
        }
    }

    private fun ensureMapReady(): Boolean {
        saveEndpoint()
        if (!isConnected) {
            setMapStatus("状态：未连接后端，请先在导航页点击一键开始导航")
            return false
        }
        return true
    }

    private fun currentMobileToken(): String {
        return prefs.getString(KEY_MOBILE_TOKEN, "").orEmpty().trim()
    }

    private fun ensureLoggedInOrRedirect(): Boolean {
        if (currentMobileToken().isNotBlank()) return true
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
        return false
    }

    private fun applyAuthHeaders(builder: Request.Builder): Request.Builder {
        val token = currentMobileToken()
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
            builder.header("X-Mobile-Token", token)
        }
        return builder
    }

    private fun onAuthExpired(source: String) {
        if (authRedirecting) return
        authRedirecting = true
        runOnUiThread {
            try {
                closeSockets()
            } catch (_: Exception) {
            }
            prefs.edit()
                .remove(KEY_MOBILE_TOKEN)
                .remove(KEY_MOBILE_EXPIRES_AT)
                .remove(KEY_MOBILE_USERNAME)
                .apply()
            Toast.makeText(this, "登录已过期，请重新登录（$source）", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
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
                val req = applyAuthHeaders(
                    Request.Builder()
                        .url(base + path)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                ).build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val json = parseJson(body)
                    if (resp.code == 401) {
                        onAuthExpired("HTTP $path")
                        runOnUiThread { callback(json, "登录已过期，请重新登录") }
                        return@use
                    }
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
                val req = applyAuthHeaders(Request.Builder().url(uriBuilder.build().toString()).get()).build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val json = parseJson(body)
                    if (resp.code == 401) {
                        onAuthExpired("GET $path")
                        runOnUiThread { callback(json, "登录已过期，请重新登录") }
                        return@use
                    }
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
            Toast.makeText(this, "请先连接后端。", Toast.LENGTH_SHORT).show()
            return
        }
        saveEndpoint()
        val baseHttp = currentBaseUrl()
        Thread {
            try {
                val req = Request.Builder()
                    .url(baseHttp + path)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                val authedReq = applyAuthHeaders(req).build()
                httpClient.newCall(authedReq).execute().use { resp ->
                    val ok = resp.isSuccessful
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 401) {
                        onAuthExpired("NAV $path")
                        return@use
                    }
                    if (!ok) throw IllegalStateException("HTTP ${resp.code}: $body")
                    val obj = JSONObject(body)
                    isNavEnabled = obj.optBoolean("nav_enabled", false)
                    val state = obj.optString("state")
                    runOnUiThread {
                        if (path.contains("/stop")) {
                            navRunning = false
                            clearGuidanceDisplay()
                            setUserStatusStopped()
                        } else if (state.equals("BLIND_NAV", ignoreCase = true) || state.equals("TRAFFIC_TEST", ignoreCase = true)) {
                            navRunning = true
                            setUserStatusRunning()
                        } else {
                            setUserStatusConnecting()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "nav command failed", e)
                runOnUiThread {
                    navRunning = false
                    setUserStatusNetworkError("导航命令失败")
                    Toast.makeText(this, "导航命令失败：${e.message}", Toast.LENGTH_LONG).show()
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
        tvStatus.text = "状态：$text"
    }

    private fun setUserStatusConnecting() {
        updateStatus("连接中…")
    }

    private fun setUserStatusRunning(traffic: String = "unknown") {
        val trafficLabel = when (traffic.lowercase(Locale.US)) {
            "red" -> "红灯"
            "green" -> "绿灯"
            else -> "未知"
        }
        updateStatus("导航中（灯态：$trafficLabel）")
        updateJourneySummary()
    }

    private fun setUserStatusStopped() {
        updateStatus("未连接")
        updateJourneySummary()
    }

    private fun setUserStatusNetworkError(reason: String) {
        updateStatus("网络异常：$reason")
    }

    private fun clearGuidanceDisplay() {
        lastGuidanceText = ""
        tvGuidance.text = getString(R.string.nav_guidance_idle)
    }

    private fun appendRiskTimelineIfNeeded(guidance: String, traffic: String, state: String, eventTimeMs: Long) {
        if (state.equals("IDLE", ignoreCase = true)) return
        val message = guidance.trim()
        if (message.isEmpty()) return

        val now = System.currentTimeMillis()
        if (message == lastRiskGuidanceText && now - lastRiskAddedAtMs < RISK_EVENT_DEDUPE_WINDOW_MS) return
        lastRiskGuidanceText = message
        lastRiskAddedAtMs = now

        val item = RiskTimelineItem(
            type = classifyRiskType(message, traffic),
            timeLabel = riskTimeFormatter.format(Date(eventTimeMs)),
            guidance = message,
            tag = "语音已播报"
        )
        if (riskEvents.size >= RISK_EVENT_MAX_COUNT) {
            riskEvents.removeFirst()
        }
        riskEvents.addLast(item)
        renderRiskTimeline()
    }

    private fun classifyRiskType(guidance: String, traffic: String): String {
        val normalized = guidance.lowercase(Locale.CHINA)
        if (traffic.equals("red", ignoreCase = true) || traffic.equals("green", ignoreCase = true)
            || normalized.contains("红灯") || normalized.contains("绿灯")
        ) {
            return "红绿灯识别"
        }
        if (normalized.contains("电动车") || normalized.contains("车辆") || normalized.contains("机动车") || normalized.contains("汽车")) {
            return "车辆接近"
        }
        if (normalized.contains("障碍") || normalized.contains("施工") || normalized.contains("围栏") || normalized.contains("台阶")) {
            return "障碍物检测"
        }
        return "通用提醒"
    }

    private fun renderRiskTimeline() {
        llRiskTimeline.removeAllViews()
        if (riskEvents.isEmpty()) {
            tvRiskEmpty.visibility = View.VISIBLE
            updateJourneySummary()
            return
        }
        tvRiskEmpty.visibility = View.GONE
        for (item in riskEvents.reversed()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    cornerRadius = 18f
                    setColor(0xFFFFFFFF.toInt())
                }
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this).apply {
                text = item.type
                setTextColor(0xFF0F172A.toInt())
                textSize = 15f
            })
            titleRow.addView(TextView(this).apply {
                text = item.timeLabel
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            card.addView(titleRow)
            card.addView(TextView(this).apply {
                text = item.guidance
                setTextColor(0xFF334155.toInt())
                textSize = 14f
                setPadding(0, 8, 0, 8)
            })
            card.addView(TextView(this).apply {
                text = item.tag
                setTextColor(0xFF0B63F6.toInt())
                textSize = 12f
                background = GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(0x1A0B63F6)
                }
                setPadding(10, 4, 10, 4)
            })
            llRiskTimeline.addView(card)
        }
        updateJourneySummary()
    }

    private fun updateJourneySummary() {
        val elapsedMin = if (navSessionStartedAtMs > 0L) {
            ((System.currentTimeMillis() - navSessionStartedAtMs) / 60000L).coerceAtLeast(0L)
        } else {
            0L
        }
        tvJourneyDurationValue.text = elapsedMin.toString()
    }

    private fun currentBaseUrl(): String {
        val hostRaw = normalizedHostInput()
        val portRaw = etPort.text?.toString()?.trim().orEmpty()
        val host = if (hostRaw.isEmpty()) DEFAULT_HOST else hostRaw
        val port = if (portRaw.matches(Regex("\\d+"))) portRaw else DEFAULT_PORT
        val prefixHost = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
        return "$prefixHost:$port"
    }

    private fun saveEndpoint() {
        val host = normalizedHostInput().ifEmpty { DEFAULT_HOST }
        val port = etPort.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_PORT }
        prefs.edit().putString(KEY_HOST, host).putString(KEY_PORT, port).apply()
    }

    private fun normalizedHostInput(): String {
        return etHost.text?.toString().orEmpty()
            .replace("。", ".")
            .replace("．", ".")
            .replace("：", ":")
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
            .trim()
    }

    private fun ensureHostUsableForDevice(host: String): Boolean {
        if (host != "10.0.2.2") return true
        if (isProbablyEmulator()) return true
        val msg = "真机不能使用 10.0.2.2，请改为电脑局域网IP（如 192.168.x.x）"
        setUserStatusNetworkError(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        return false
    }

    private fun isProbablyEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val manu = Build.MANUFACTURER.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        val device = Build.DEVICE.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)
        return fp.contains("generic")
            || fp.contains("emulator")
            || model.contains("emulator")
            || model.contains("android sdk built for")
            || manu.contains("genymotion")
            || (brand.contains("generic") && device.contains("generic"))
            || product.contains("sdk_gphone")
            || product.contains("emulator")
    }

    private fun closeSockets() {
        suppressReconnectUntilMs = System.currentTimeMillis() + CORE_WS_RECONNECT_SUPPRESS_MS
        coreReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        coreReconnectRunnable = null
        coreReconnectScheduled = false
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
        wsCameraConnected = false
        wsGuidanceConnected = false
        wsAudioConnected = false
        wsViewerConnected = false
        refreshCoreConnectionState()
        navRunning = false
        lastNavAudioAtMs = 0L
    }

    override fun onDestroy() {
        appInForeground = false
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
        assistantResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        assistantResumeRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        closeSockets()
        audioTrack?.stop()
        audioTrack?.release()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
        try {
            baiduMapView?.onResume()
        } catch (e: Throwable) {
            Log.e(TAG, "map resume failed", e)
        }
    }

    override fun onPause() {
        appInForeground = false
        stopProxyAsrRecognition(sendStop = true)
        stopSparkAsr(true)
        stopSparkTtsPlayback()
        assistantResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        assistantResumeRunnable = null
        pendingAssistantTtsText = ""
        try {
            baiduMapView?.onPause()
        } catch (e: Throwable) {
            Log.e(TAG, "map pause failed", e)
        }
        super.onPause()
    }
}

















