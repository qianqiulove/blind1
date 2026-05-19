package com.blind.v1

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var viewerImage: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvGuidance: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder().build()

    private var wsCamera: WebSocket? = null
    private var wsGuidance: WebSocket? = null
    private var wsAudio: WebSocket? = null
    private var wsViewer: WebSocket? = null
    private var isConnected = false
    private var isNavEnabled = false
    private var openCount = 0
    private var lastGuidanceText: String = ""
    private var viewerEnabled: Boolean = false
    private var lastFrameSentAtMs: Long = 0L
    @Volatile private var viewerDecodeBusy: Boolean = false

    private var audioTrack: AudioTrack? = null

    companion object {
        private const val TAG = "blind-v1"
        // Change to your backend machine IP.
        private const val BASE_URL = "http://10.0.2.2:8088"
        private const val FRAME_SEND_INTERVAL_MS = 35L
        private const val CAMERA_JPEG_QUALITY = 48
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        viewerImage = findViewById(R.id.viewerImage)
        tvStatus = findViewById(R.id.tvStatus)
        tvGuidance = findViewById(R.id.tvGuidance)

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectSockets() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { callNavCommand("/api/nav/start") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { callNavCommand("/api/nav/stop") }
        findViewById<Button>(R.id.btnAiReserved).setOnClickListener {
            Toast.makeText(this, "AI assistant is reserved in v1.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnMapReserved).setOnClickListener {
            Toast.makeText(this, "Map API is reserved in v1.", Toast.LENGTH_SHORT).show()
        }
        val btnDebug = findViewById<Button>(R.id.btnDebugOverlay)
        btnDebug.setOnClickListener {
            viewerEnabled = !viewerEnabled
            viewerImage.visibility = if (viewerEnabled) View.VISIBLE else View.GONE
            previewView.visibility = if (viewerEnabled) View.GONE else View.VISIBLE
            btnDebug.text = if (viewerEnabled) "Server Overlay On" else "Server Overlay Off"
            if (!viewerEnabled) viewerImage.setImageBitmap(null)
        }

        ensureCameraPermissionAndStart()
        initAudioTrack()
    }

    private fun ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.getSurfaceProvider())
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(512, 288))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image -> onCameraFrame(image) }
                }
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
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
            val now = System.currentTimeMillis()
            if (now - lastFrameSentAtMs < FRAME_SEND_INTERVAL_MS) return
            val jpeg = ImageProxyUtils.toJpegBytes(image, quality = CAMERA_JPEG_QUALITY) ?: return
            lastFrameSentAtMs = now
            wsCamera?.send(jpeg.toByteString())
        } catch (e: Exception) {
            Log.e(TAG, "frame send failed", e)
        } finally {
            image.close()
        }
    }

    private fun connectSockets() {
        if (isConnected) return
        openCount = 0
        val baseWs = BASE_URL.replace("http://", "ws://").replace("https://", "wss://")

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
                        if (guidance.isNotEmpty()) {
                            lastGuidanceText = guidance
                        }
                        runOnUiThread {
                            tvGuidance.text = "Guidance: $lastGuidanceText"
                            updateStatus("State=$state traffic=$traffic")
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

        wsViewer = httpClient.newWebSocket(
            Request.Builder().url("$baseWs/ws/viewer").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openCount += 1
                    if (openCount >= 3) isConnected = true
                    runOnUiThread { updateStatus("Viewer WS connected ($openCount/4)") }
                    webSocket.send("hello")
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
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
                                if (viewerEnabled) {
                                    viewerImage.setImageBitmap(bmp)
                                }
                            } finally {
                                viewerDecodeBusy = false
                            }
                        }
                    } catch (e: Exception) {
                        viewerDecodeBusy = false
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "viewer ws failed", t)
                    runOnUiThread { updateStatus("Viewer WS failed: ${t.message}") }
                }
            }
        )

        updateStatus("Sockets connecting")
    }

    private fun callNavCommand(path: String) {
        if (!isConnected) {
            Toast.makeText(this, "Please connect first.", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val req = Request.Builder()
                    .url(BASE_URL + path)
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

    override fun onDestroy() {
        super.onDestroy()
        isNavEnabled = false
        isConnected = false
        wsCamera?.close(1000, "bye")
        wsGuidance?.close(1000, "bye")
        wsAudio?.close(1000, "bye")
        wsViewer?.close(1000, "bye")
        audioTrack?.stop()
        audioTrack?.release()
        cameraExecutor.shutdown()
    }
}
