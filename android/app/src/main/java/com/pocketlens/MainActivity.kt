package com.pocketlens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity() {
    private val background = HandlerThread("PocketLensCamera").apply { start() }
    private val cameraHandler by lazy { Handler(background.looper) }
    private val frameProcessorThread = HandlerThread("PocketLensFrameProcessor").apply { start() }
    private val frameProcessorHandler by lazy { Handler(frameProcessorThread.looper) }
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val pendingJpeg = AtomicReference<ByteArray?>(null)
    private val frameProcessing = AtomicBoolean(false)
    private val colors = Palette()
    private lateinit var deviceList: LinearLayout
    private lateinit var cameraSelector: RadioGroup
    private lateinit var previewView: TextureView
    private lateinit var previewPlaceholder: TextView
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var scanButton: Button
    @Volatile private var selectedPc: PcDevice? = null
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var orientationListener: OrientationEventListener? = null
    @Volatile private var sensorOrientation = 0
    @Volatile private var deviceOrientation = 0
    @Volatile private var selectedCameraId = REAR_CAMERA_ID
    @Volatile private var cameraGeneration = 0
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(colors.background)
        }
        val root = ScrollView(this).apply {
            setBackgroundColor(colors.background)
            addView(content)
        }
        val logo = TextView(this).apply {
            text = "P"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(colors.background)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(colors.cyan)
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
        content.addView(logo)
        content.addView(TextView(this).apply {
            text = "PocketLens"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(22), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Find your PC and connect—no address or code needed."
            textSize = 15f
            setTextColor(colors.muted)
            setPadding(0, 0, 0, dp(22))
        })
        content.addView(TextView(this).apply {
            text = "Computers on your local network can connect without a pairing code. Use PocketLens only on a trusted Wi-Fi network."
            textSize = 13f
            setTextColor(Color.rgb(244, 214, 179))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(56, 36, 22))
        }, spacedParams())
        scanButton = Button(this).apply {
            text = "Find PCs"
            isAllCaps = false
            setOnClickListener { discoverDevices() }
        }
        content.addView(scanButton, spacedParams())
        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(deviceList, spacedParams())
        content.addView(TextView(this).apply {
            text = "Camera preview"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, dp(8))
        })
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(220)
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (running && camera == null) {
                        cameraHandler.post {
                            if (running && camera == null) {
                                try {
                                    openCamera()
                                } catch (error: Exception) {
                                    stopStream("Camera could not start: ${error.message ?: "unknown error"}")
                                }
                            }
                        }
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            }
        }
        previewPlaceholder = TextView(this).apply {
            text = "Preview appears when the camera is connected"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(colors.muted)
        }
        previewFrame.addView(previewView)
        previewFrame.addView(previewPlaceholder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(220)
        ))
        content.addView(previewFrame, spacedParams())
        content.addView(TextView(this).apply {
            text = "Choose camera"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, 0)
        })
        cameraSelector = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rearCamera = RadioButton(this).apply {
            id = REAR_CAMERA_ID
            text = "Rear"
            setTextColor(Color.WHITE)
        }
        val frontCamera = RadioButton(this).apply {
            id = FRONT_CAMERA_ID
            text = "Front"
            setTextColor(Color.WHITE)
        }
        cameraSelector.addView(rearCamera, RadioGroup.LayoutParams(0, dp(52), 1f))
        cameraSelector.addView(frontCamera, RadioGroup.LayoutParams(0, dp(52), 1f))
        selectedCameraId = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getInt(SELECTED_CAMERA, REAR_CAMERA_ID)
                .let { if (it == FRONT_CAMERA_ID) FRONT_CAMERA_ID else REAR_CAMERA_ID }
        cameraSelector.check(selectedCameraId)
        cameraSelector.setOnCheckedChangeListener { _, checkedId ->
            selectedCameraId = if (checkedId == FRONT_CAMERA_ID) FRONT_CAMERA_ID else REAR_CAMERA_ID
            getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(SELECTED_CAMERA, selectedCameraId)
                .apply()
            if (running) {
                switchCamera()
            } else {
                statusText.text = "${if (selectedCameraId == FRONT_CAMERA_ID) "Front" else "Rear"} camera selected."
            }
        }
        content.addView(cameraSelector, spacedParams())

        connectButton = Button(this).apply {
            text = "Connect camera"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(colors.indigo)
            setOnClickListener { requestCameraAndConnect() }
        }
        content.addView(connectButton, spacedParams())
        statusText = TextView(this).apply {
            text = "Tap Find PCs to scan your local Wi-Fi network."
            textSize = 14f
            setTextColor(colors.muted)
            setPadding(0, dp(14), 0, 0)
        }
        content.addView(statusText)
        setContentView(root)
    }

    private fun spacedParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) }

    private fun discoverDevices() {
        if (running) return
        scanButton.isEnabled = false
        selectedPc = null
        connectButton.isEnabled = false
        deviceList.removeAllViews()
        statusText.text = "Scanning this Wi-Fi network…"

        Thread({
            val found = linkedMapOf<String, PcDevice>()
            var scanError: String? = null
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 250
                    val request = DISCOVERY_MESSAGE.toByteArray(Charsets.UTF_8)
                    val broadcasts = linkedSetOf<InetAddress>()
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val networkInterface = interfaces.nextElement()
                        if (!networkInterface.isUp || networkInterface.isLoopback) continue
                        for (interfaceAddress in networkInterface.interfaceAddresses) {
                            val broadcast = interfaceAddress.broadcast
                            if (interfaceAddress.address is Inet4Address && broadcast != null) {
                                broadcasts.add(broadcast)
                            }
                        }
                    }
                    broadcasts.add(InetAddress.getByName("255.255.255.255"))
                    for (broadcast in broadcasts) {
                        val packet = DatagramPacket(request, request.size, broadcast, DISCOVERY_PORT)
                        try {
                            socket.send(packet)
                        } catch (_: IOException) {
                        }
                    }

                    val buffer = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline) {
                        socket.soTimeout = (deadline - System.currentTimeMillis())
                            .coerceIn(50, 300).toInt()
                        val reply = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(reply)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val details = JSONObject(String(reply.data, reply.offset, reply.length, Charsets.UTF_8))
                        if (details.optString("service") != "pocketlens") continue
                        val id = details.optString("deviceId")
                        val pc = PcDevice(
                            id = id,
                            name = details.optString("name", "PocketLens PC"),
                            address = reply.address.hostAddress ?: continue,
                            port = details.optInt("port", DEFAULT_HTTP_PORT)
                        )
                        if (id.isNotBlank() && pc.port in 1..65535) found[id] = pc
                    }
                }
            } catch (error: Exception) {
                scanError = error.message
                runOnUiThread {
                    statusText.text = "Network scan failed: ${scanError ?: "check Wi-Fi"}"
                }
            }

            runOnUiThread {
                scanButton.isEnabled = true
                if (found.isEmpty()) {
                    statusText.text = if (scanError != null) {
                        "Network scan failed: $scanError"
                    } else {
                        "No PocketLens PCs found. Check both devices are on the same Wi-Fi and try again."
                    }
                    return@runOnUiThread
                }
                statusText.text = "Select your PC:"
                for (pc in found.values) {
                    val button = Button(this).apply {
                        text = "${pc.name}\n${pc.address}"
                        isAllCaps = false
                        setOnClickListener {
                            selectedPc = pc
                            connectButton.isEnabled = true
                            statusText.text = "${pc.name} selected. Tap Connect camera."
                        }
                    }
                    deviceList.addView(button, spacedParams())
                }
            }
        }, "PocketLensDiscovery").start()
    }

    private fun requestCameraAndConnect() {
        if (running) {
            stopStream("Disconnected.")
            return
        }
        if (selectedPc == null) {
            statusText.text = "Tap Find PCs and select your computer first."
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
            return
        }
        startStream()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startStream()
        } else if (requestCode == CAMERA_PERMISSION) {
            statusText.text = "Camera permission is required to stream."
        }
    }

    private fun startStream() {
        val pc = selectedPc ?: run {
            statusText.text = "Scan and select a PC first."
            return
        }
        try {
            CameraKeepAliveService.start(this)
        } catch (error: Exception) {
            statusText.text = "Could not keep camera active in the background: ${error.message ?: "foreground service unavailable"}"
            return
        }
        running = true
        deviceOrientation = displayRotationDegrees()
        enableOrientationUpdates()
        connectButton.text = "Disconnect"
        previewPlaceholder.visibility = android.view.View.VISIBLE
        statusText.text = "Connecting to ${pc.name}…"
        Thread({
            var output: DataOutputStream? = null
            try {
                val url = URL("http://${pc.address}:${pc.port}/ingest")
                val opened = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 0
                    setChunkedStreamingMode(16 * 1024)
                    setRequestProperty("Content-Type", "application/x-pocketlens-frames")
                }
                connection = opened
                output = DataOutputStream(opened.outputStream)
                runOnUiThread { statusText.text = "Connected — starting camera…" }
                if (previewView.isAvailable) {
                    cameraHandler.post {
                        try {
                            if (running && camera == null) openCamera()
                        } catch (error: Exception) {
                            stopStream("Camera could not start: ${error.message ?: "unknown error"}")
                        }
                    }
                }

                while (running) {
                    val frame = latestFrame.getAndSet(null)
                    if (frame == null) {
                        Thread.sleep(8)
                        continue
                    }
                    output.writeInt(frame.size)
                    output.write(frame)
                    output.flush()
                }
            } catch (error: Exception) {
                if (running) {
                    runOnUiThread {
                        statusText.text = "Connection failed: ${error.message ?: "check the PC is running and on Wi-Fi"}"
                    }
                }
            } finally {
                try { output?.close() } catch (_: IOException) { }
                connection?.disconnect()
                connection = null
                closeCamera()
                running = false
                stopService(Intent(this, CameraKeepAliveService::class.java))
                runOnUiThread {
                    connectButton.text = "Connect camera"
                    connectButton.isEnabled = selectedPc != null
                    if (statusText.text.startsWith("Connected")) statusText.text = "Disconnected."
                }
            }
        }, "PocketLensSender").start()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!running || camera != null) return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val generation = ++cameraGeneration
        val requestedFacing = if (selectedCameraId == FRONT_CAMERA_ID) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == requestedFacing
        } ?: throw IOException(
            "The selected ${if (requestedFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "rear"} camera is not available."
        )

        val characteristics = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("Camera does not support JPEG output.")
        val jpegSizes: List<android.util.Size> = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
            ?.asList()
            ?.filter { maxOf(it.width, it.height) <= 3840 && minOf(it.width, it.height) <= 2160 }
            ?: emptyList()
        val size = jpegSizes
            .filter { it.width.toLong() * 9 == it.height.toLong() * 16 || it.height.toLong() * 9 == it.width.toLong() * 16 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: jpegSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IOException("Camera has no supported output up to 3840×2160.")
        val cropToWidescreen = size.width.toLong() * 9 != size.height.toLong() * 16 &&
            size.height.toLong() * 9 != size.width.toLong() * 16
        val outputSize = if (!cropToWidescreen) {
            size
        } else if (size.width.toLong() * 9 > size.height.toLong() * 16) {
            android.util.Size(size.height * 16 / 9, size.height)
        } else {
            android.util.Size(size.width, size.width * 9 / 16)
        }

        imageReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                val buffer = image?.planes?.firstOrNull()?.buffer
                if (buffer != null) {
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (cropToWidescreen) {
                        pendingJpeg.set(bytes)
                        scheduleFrameProcessing()
                    } else {
                        latestFrame.set(bytes)
                    }
                    runOnUiThread {
                        if (statusText.text.startsWith("Connected")) {
                            statusText.text = "Live • ${outputSize.width}×${outputSize.height}"
                        }
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e("PocketLens", "Could not prepare camera frame", error)
                runOnUiThread {
                    statusText.text = "Could not prepare a 16:9 camera frame: ${error.message ?: "image conversion failed"}"
                }
            } finally {
                image?.close()
            }
        }, cameraHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!running || generation != cameraGeneration) {
                    device.close()
                    return
                }
                camera = device
                val readerSurface = imageReader?.surface ?: return
                val texture = previewView.surfaceTexture ?: return
                val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                    ?.filter { it.width <= 1920 && it.height <= 1080 }
                    ?.maxByOrNull { it.width * it.height }
                    ?: size
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                val previewOutput = Surface(texture)
                previewSurface = previewOutput
                device.createCaptureSession(listOf(readerSurface, previewOutput), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running || generation != cameraGeneration) {
                            session.close()
                            return
                        }
                        captureSession = session
                        captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(readerSurface)
                            addTarget(previewOutput)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                        }
                        session.setRepeatingRequest(captureRequestBuilder!!.build(), null, cameraHandler)
                        runOnUiThread {
                            previewPlaceholder.visibility = android.view.View.GONE
                            statusText.text = "Connected — ${if (selectedCameraId == FRONT_CAMERA_ID) "front" else "rear"} camera live • ${size.width}×${size.height}"
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (generation != cameraGeneration) {
                            session.close()
                            return
                        }
                        runOnUiThread { statusText.text = "Could not configure the camera." }
                        stopStream("Camera setup failed.")
                    }
                }, cameraHandler)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (generation == cameraGeneration) {
                    camera = null
                    if (running) stopStream("Camera disconnected.")
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (generation == cameraGeneration) {
                    camera = null
                    if (running) stopStream("Camera error ($error).")
                }
            }
        }, cameraHandler)
    }

    private fun scheduleFrameProcessing() {
        if (!frameProcessing.compareAndSet(false, true)) return
        frameProcessorHandler.post {
            while (true) {
                val jpeg = pendingJpeg.getAndSet(null)
                if (jpeg != null) {
                    try {
                        val cropped = cropJpegToWidescreen(jpeg)
                        if (running) latestFrame.set(cropped)
                    } catch (error: Exception) {
                        android.util.Log.e("PocketLens", "Could not crop camera frame to 16:9", error)
                        runOnUiThread {
                            statusText.text = "Could not prepare a 16:9 camera frame: ${error.message ?: "image conversion failed"}"
                        }
                    }
                }
                frameProcessing.set(false)
                if (pendingJpeg.get() == null || !frameProcessing.compareAndSet(false, true)) break
            }
        }
    }

    private fun cropJpegToWidescreen(jpeg: ByteArray): ByteArray {
        val decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
            ?: throw IOException("Could not decode the camera JPEG.")
        try {
            val width = decoder.width
            val height = decoder.height
            val crop = if (width.toLong() * 9 > height.toLong() * 16) {
                val cropWidth = height * 16 / 9
                val left = (width - cropWidth) / 2
                Rect(left, 0, left + cropWidth, height)
            } else {
                val cropHeight = width * 9 / 16
                val top = (height - cropHeight) / 2
                Rect(0, top, width, top + cropHeight)
            }
            val bitmap = decoder.decodeRegion(crop, BitmapFactory.Options())
                ?: throw IOException("Could not crop the camera JPEG to 16:9.")
            val croppedJpeg = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    bitmap.recycle()
                    throw IOException("Could not encode the cropped camera frame.")
                }
                bitmap.recycle()
                output.toByteArray()
            }
            return preserveExifOrientation(jpeg, croppedJpeg)
        } finally {
            decoder.recycle()
        }
    }

    private fun preserveExifOrientation(original: ByteArray, cropped: ByteArray): ByteArray {
        val exifSegment = findExifSegment(original) ?: return cropped
        if (cropped.size < 4 || cropped[0] != 0xFF.toByte() || cropped[1] != 0xD8.toByte()) {
            throw IOException("JPEG encoder returned an invalid image.")
        }
        var insertion = 2
        if (cropped.size >= 6 && cropped[2] == 0xFF.toByte() && cropped[3] == 0xE0.toByte()) {
            val app0Length = ((cropped[4].toInt() and 0xFF) shl 8) or (cropped[5].toInt() and 0xFF)
            insertion = 4 + app0Length
            if (insertion > cropped.size) throw IOException("JPEG encoder returned an invalid APP0 segment.")
        }
        return ByteArray(cropped.size + exifSegment.size).also { result ->
            cropped.copyInto(result, 0, 0, insertion)
            exifSegment.copyInto(result, insertion)
            cropped.copyInto(result, insertion + exifSegment.size, insertion, cropped.size)
        }
    }

    private fun findExifSegment(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var offset = 2
        while (offset + 4 <= jpeg.size && jpeg[offset] == 0xFF.toByte()) {
            val markerStart = offset
            while (offset < jpeg.size && jpeg[offset] == 0xFF.toByte()) offset++
            if (offset >= jpeg.size) return null
            val marker = jpeg[offset].toInt() and 0xFF
            offset++
            if (marker == 0xDA || marker == 0xD9) return null
            if (marker in 0xD0..0xD7 || marker == 0x01) continue
            if (offset + 2 > jpeg.size) return null
            val segmentLength = ((jpeg[offset].toInt() and 0xFF) shl 8) or (jpeg[offset + 1].toInt() and 0xFF)
            val segmentEnd = offset + segmentLength
            if (segmentLength < 2 || segmentEnd > jpeg.size) return null
            if (marker == 0xE1 && segmentLength >= 8 &&
                jpeg[offset + 2] == 'E'.code.toByte() &&
                jpeg[offset + 3] == 'x'.code.toByte() &&
                jpeg[offset + 4] == 'i'.code.toByte() &&
                jpeg[offset + 5] == 'f'.code.toByte() &&
                jpeg[offset + 6] == 0.toByte() && jpeg[offset + 7] == 0.toByte()
            ) {
                return jpeg.copyOfRange(markerStart, segmentEnd)
            }
            offset = segmentEnd
        }
        return null
    }

    private fun switchCamera() {
        runOnUiThread { previewPlaceholder.visibility = android.view.View.VISIBLE }
        statusText.text = "Switching to ${if (selectedCameraId == FRONT_CAMERA_ID) "front" else "rear"} camera…"
        cameraHandler.post {
            closeCamera()
            if (!running || !previewView.isAvailable) return@post
            try {
                openCamera()
            } catch (error: Exception) {
                stopStream("Camera could not switch: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun enableOrientationUpdates() {
        if (orientationListener == null) {
            orientationListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val rounded = ((orientation + 45) / 90 * 90) % 360
                    if (rounded == deviceOrientation) return
                    deviceOrientation = rounded
                    cameraHandler.post {
                        val builder = captureRequestBuilder ?: return@post
                        val session = captureSession ?: return@post
                        try {
                            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                            session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        } catch (error: Exception) {
                            if (running) {
                                runOnUiThread {
                                    statusText.text = "Could not update camera orientation: ${error.message ?: "camera request failed"}"
                                }
                            }
                        }
                    }
                }
            }
        }
        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        }
    }

    private fun displayRotationDegrees(): Int {
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun jpegOrientation(): Int {
        val isFrontCamera = selectedCameraId == FRONT_CAMERA_ID
        return if (isFrontCamera) {
            (sensorOrientation + deviceOrientation) % 360
        } else {
            (sensorOrientation - deviceOrientation + 360) % 360
        }
    }

    private fun stopStream(message: String) {
        running = false
        latestFrame.set(null)
        pendingJpeg.set(null)
        orientationListener?.disable()
        closeCamera()
        connection?.disconnect()
        stopService(Intent(this, CameraKeepAliveService::class.java))
        runOnUiThread {
            statusText.text = message
            connectButton.text = "Connect camera"
            connectButton.isEnabled = selectedPc != null
            previewPlaceholder.visibility = android.view.View.VISIBLE
        }
    }

    private fun closeCamera() {
        cameraGeneration++
        try { captureSession?.close() } catch (_: Exception) { }
        captureSession = null
        captureRequestBuilder = null
        try { camera?.close() } catch (_: Exception) { }
        camera = null
        try { previewSurface?.release() } catch (_: Exception) { }
        previewSurface = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class PcDevice(val id: String, val name: String, val address: String, val port: Int)

    override fun onDestroy() {
        stopStream("Disconnected.")
        orientationListener?.disable()
        background.quitSafely()
        frameProcessorThread.quitSafely()
        super.onDestroy()
    }

    private data class Palette(
        val background: Int = Color.rgb(8, 13, 26),
        val field: Int = Color.rgb(24, 34, 57),
        val muted: Int = Color.rgb(155, 168, 200),
        val indigo: Int = Color.rgb(92, 103, 255),
        val cyan: Int = Color.rgb(82, 213, 232)
    )

    companion object {
        private const val CAMERA_PERMISSION = 41
        private const val DISCOVERY_PORT = 8788
        private const val DEFAULT_HTTP_PORT = 8787
        private const val DISCOVERY_TIMEOUT_MS = 1800L
        private const val DISCOVERY_MESSAGE = "POCKETLENS_DISCOVER_V1"
        private const val PREFERENCES = "pocketlens_settings"
        private const val SELECTED_CAMERA = "selected_camera"
        private const val REAR_CAMERA_ID = 1
        private const val FRONT_CAMERA_ID = 2
    }
}
