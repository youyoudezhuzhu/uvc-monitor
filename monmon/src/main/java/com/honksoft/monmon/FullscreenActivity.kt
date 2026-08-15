package com.honksoft.monmon

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView
import com.jiangdg.uac.UACAudio
import com.jiangdg.uac.UACAudioCallBack
import com.honksoft.monmon.MainActivity.Companion.EXTRA_DEVICE_ID
import com.honksoft.monmon.MainActivity.Companion.EXTRA_DEVICE_NAME
import com.honksoft.monmon.MainActivity.Companion.REQUIRED_PERMISSIONS
import com.honksoft.monmon.databinding.ActivityFullscreenBinding
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 全屏实时预览：USB 摄像头 / HDMI 采集卡（AUSBC 引擎，支持 UAC 音频）
 * 功能：峰焦辅助（OpenCV 边缘叠加）、分辨率选择、画面旋转、UAC 音频播放、捏合缩放
 */
class FullscreenActivity : AppCompatActivity() {

    private var cameraClient: CameraClient? = null
    private lateinit var binding: ActivityFullscreenBinding
    private lateinit var imageTools: ImageTools

    private var cameraViewMain: AspectRatioSurfaceView? = null
    private var cameraOverlay: ImageView? = null

    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var panGestureDetector: GestureDetector? = null
    private var isPinching = false

    private var rotationIndex = 0
    private var currentSize: PreviewSize? = null

    private var uacAudio: UACAudio? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var peakEnabled = false
    @Volatile
    private var dontEdge = false
    @Volatile
    private var shouldMerge = true
    @Volatile
    private var color = 0
    @Volatile
    private var threshold = 40

    fun Float.map(inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
        return (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = REQUIRED_PERMISSIONS.all { permissions[it] == true }
            if (allGranted) {
                initCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    /** 峰焦帧回调（NV21 → OpenCV 边缘叠加） */
    private val previewCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(
            data: ByteArray?,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
        ) {
            if (!peakEnabled) return
            val nv21 = data ?: return
            processFrame(nv21, width, height)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Toast.makeText(this, "OpenCV 初始化失败", Toast.LENGTH_LONG).show()
        }

        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imageTools = ImageTools(this)

        cameraViewMain = binding.svCameraViewMain
        cameraOverlay = binding.svCameraOverlay
        cameraViewMain?.setAspectRatio(16, 9)

        scaleGestureDetector = ScaleGestureDetector(
            this,
            ScaleListener(listOfNotNull(cameraViewMain, cameraOverlay))
        )
        panGestureDetector = GestureDetector(this, PanListener(listOfNotNull(cameraViewMain, cameraOverlay)))

        // 手势：单点点击切换控制条，双指捏合缩放/拖动
        binding.root.setOnTouchListener { _, event ->
            if (event.pointerCount >= 2) {
                panGestureDetector?.onTouchEvent(event)
                scaleGestureDetector?.onTouchEvent(event)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> isPinching = false
                MotionEvent.ACTION_POINTER_DOWN -> isPinching = true
                MotionEvent.ACTION_UP -> {
                    if (!isPinching && scaleGestureDetector?.isInProgress != true) {
                        toggleControls()
                    }
                }
            }
            true
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBrightness.setOnClickListener { showBrightnessDialog() }
        binding.btnPeak.setOnClickListener {
            peakEnabled = !peakEnabled
            applyPeakMode()
        }
        binding.btnRotate.setOnClickListener {
            rotationIndex = (rotationIndex + 1) % 4
            applyRotation()
        }
        binding.btnResolution.setOnClickListener { showResolutionDialog() }
        binding.btnSettings.setOnClickListener {
            ViewSettingsFragment().show(supportFragmentManager, "view_settings_dialog")
        }

        binding.tvTitle.text = intent.getStringExtra(EXTRA_DEVICE_NAME)
            ?: getString(R.string.app_name)

        // 收集峰焦设置
        lifecycleScope.launch {
            dataStore.data.collect { prefs ->
                shouldMerge =
                    prefs[PreferenceKeys.PEAK_VISIBILITY] != PrefsPeakVisiblityType.EDGES_ONLY.ordinal
                dontEdge =
                    prefs[PreferenceKeys.PEAK_VISIBILITY] == PrefsPeakVisiblityType.OFF.ordinal
                color = prefs[PreferenceKeys.PEAK_COLOR] ?: 0
                threshold = prefs[PreferenceKeys.PEAK_THRESHOLD] ?: 40
            }
        }
    }

    override fun onStart() {
        super.onStart()
        initCamera()
    }

    override fun onStop() {
        super.onStop()
        releaseCamera()
    }

    private fun initCamera() {
        if (!allPermissionsGranted()) {
            requestPermissions()
            return
        }
        if (cameraClient == null) {
            val request = CameraRequest.Builder()
                .setPreviewWidth(1280)
                .setPreviewHeight(720)
                .setRenderMode(CameraRequest.RenderMode.NORMAL)
                .setAudioSource(CameraRequest.AudioSource.SOURCE_DEV_MIC)
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                .setAspectRatioShow(true)
                .setRawPreviewData(false)
                .setCaptureRawImage(false)
                .create()

            cameraClient = CameraClient.Builder(this)
                .setCameraRequest(request)
                .setCameraStrategy(CameraUvcStrategy(this))
                .setEnableGLES(false)
                .build()
            cameraClient?.addPreviewDataCallBack(previewCallback)

            val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
            if (deviceId > 0) {
                showStatus(getString(R.string.device_connecting, binding.tvTitle.text))
                cameraClient?.switchCamera(deviceId.toString())
            } else {
                showStatus(getString(R.string.no_device))
            }

            // 打开预览（渲染到 SurfaceView）+ 后台启动 UAC 音频播放
            cameraClient?.openCamera(binding.svCameraViewMain)
            Thread { startUacAudio() }.start()
            applyPeakMode()
        }
    }

    private fun releaseCamera() {
        stopUacAudio()
        cameraClient?.removePreviewDataCallBack(previewCallback)
        cameraClient?.closeCamera()
        cameraClient = null
    }

    /** 自接管 UAC 音频播放：UACAudio 直接喂 AudioTrack（绕开 AUSBC 队列丢包导致的失真） */
    private fun startUacAudio() {
        try {
            val strategy = cameraClient?.getCameraStrategy() as? CameraUvcStrategy ?: return
            val ctrlBlock = strategy.getUsbControlBlock() ?: return

            val audio = UACAudio()
            audio.init(ctrlBlock)

            val sampleRate = audio.getSampleRate().takeIf { it > 0 } ?: 48000
            val channelCount = audio.getChannelCount().takeIf { it > 0 } ?: 2
            val bitResolution = audio.getBitResolution().takeIf { it > 0 } ?: 16
            val channelMask = if (channelCount >= 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val encoding = if (bitResolution >= 16) {
                AudioFormat.ENCODING_PCM_16BIT
            } else {
                AudioFormat.ENCODING_PCM_8BIT
            }
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            // PCM 回调直接写 AudioTrack（write 自带背压，不丢数据）
            audio.addAudioCallBack(object : UACAudioCallBack {
                override fun pcmData(data: ByteArray) {
                    try {
                        audioTrack?.write(data, 0, data.size)
                    } catch (e: Exception) {
                        Log.w(TAG, "audio write err: ${e.message}")
                    }
                }
            })
            audio.startRecording()

            uacAudio = audio
            audioTrack = track
            runOnUiThread { hideStatus() }
            Log.i(TAG, "UAC audio started: ${sampleRate}Hz ${channelCount}ch ${bitResolution}bit")
        } catch (e: Exception) {
            Log.w(TAG, "UAC audio init failed: ${e.message}")
        }
    }

    private fun stopUacAudio() {
        try {
            uacAudio?.stopRecording()
            uacAudio?.release()
        } catch (e: Exception) {
        }
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
        }
        audioTrack?.release()
        uacAudio = null
        audioTrack = null
    }

    /** 峰焦开：帧处理 overlay；峰焦关：SurfaceView 直通（更流畅） */
    private fun applyPeakMode() {
        updatePeakButton()
        if (peakEnabled) {
            cameraViewMain?.visibility = View.GONE
            cameraOverlay?.visibility = View.VISIBLE
        } else {
            cameraOverlay?.visibility = View.GONE
            cameraViewMain?.visibility = View.VISIBLE
        }
    }

    private fun updatePeakButton() {
        val tint = ContextCompat.getColor(
            this,
            if (peakEnabled) R.color.secondary else R.color.text_tertiary
        )
        binding.btnPeak.imageTintList = ColorStateList.valueOf(tint)
    }

    private fun applyRotation() {
        val type = when (rotationIndex) {
            1 -> RotateType.ANGLE_90
            2 -> RotateType.ANGLE_180
            3 -> RotateType.ANGLE_270
            else -> RotateType.ANGLE_0
        }
        cameraClient?.setRotateType(type)
    }

    /** 画面亮度调节（UVC 亮度控制，解决采集卡过曝） */
    private fun showBrightnessDialog() {
        val strategy = cameraClient?.getCameraStrategy() as? CameraUvcStrategy ?: return
        val max = strategy.getBrightnessMax().coerceAtLeast(100)
        val current = strategy.getBrightness().coerceIn(0, max)

        val seek = SeekBar(this).apply {
            this.max = max
            this.progress = current
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.brightness_title)
            .setView(seek)
            .setPositiveButton(R.string.done, null)
            .setNegativeButton(R.string.reset) { _, _ -> strategy.resetBrightness() }
            .create()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                strategy.setBrightness(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
        dialog.show()
    }

    private fun showResolutionDialog() {
        val sizes = cameraClient?.getAllPreviewSizes() ?: return
        if (sizes.isEmpty()) return
        val labels = sizes.map { "${it.width}×${it.height}" }
        val currentIndex = sizes.indexOfFirst {
            it.width == currentSize?.width && it.height == currentSize?.height
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.resolution_title)
            .setSingleChoiceItems(labels.toTypedArray(), currentIndex) { d, which ->
                currentSize = sizes[which]
                val ok = cameraClient?.updateResolution(sizes[which].width, sizes[which].height) ?: false
                if (ok) {
                    binding.tvResolution.text =
                        "${sizes[which].width}×${sizes[which].height}"
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    /** NV21 帧 → OpenCV 边缘叠加 → Bitmap（峰焦模式） */
    private fun processFrame(nv21: ByteArray, width: Int, height: Int) {
        if (dontEdge) {
            val bmp: Bitmap = imageTools.nv21ToBitmap(nv21, width, height)
            runOnUiThread { cameraOverlay?.setImageBitmap(bmp) }
            return
        }

        var yuv = Mat(height + height / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        var rgba = Mat(width, height, CvType.CV_8UC1)
        Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGB_NV21, 4)

        val imgGray = Mat()
        val cannyEdges = Mat()
        val colorized = Mat()
        val mergedImage = Mat()

        Imgproc.cvtColor(rgba, imgGray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(imgGray, imgGray, org.opencv.core.Size(5.0, 5.0), 6.0, 6.0)

        val mappedThreshold = (threshold.toFloat()).map(0.0f, 100.0f, -4.0f, 1.5f)
        Imgproc.Canny(imgGray, cannyEdges, 80.0 * -mappedThreshold, 100.0 * -mappedThreshold)
        Imgproc.cvtColor(cannyEdges, colorized, Imgproc.COLOR_GRAY2RGBA)

        val mappedColor = when (PrefsPeakColorType.entries[color]) {
            PrefsPeakColorType.RED -> Scalar(5.0, 0.0, 0.0)
            PrefsPeakColorType.GREEN -> Scalar(0.0, 5.0, 0.0)
            PrefsPeakColorType.BLUE -> Scalar(0.0, 0.0, 5.0)
            PrefsPeakColorType.YELLOW -> Scalar(5.0, 5.0, 0.0)
            PrefsPeakColorType.WHITE -> Scalar(1.0, 1.0, 1.0)
        }
        Core.multiply(colorized, mappedColor, colorized)

        val kernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(5.0, 5.0))
        Imgproc.dilate(colorized, colorized, kernel, Point(-1.0, -1.0), 1)

        if (shouldMerge) {
            Core.addWeighted(rgba, 1.0, colorized, 0.7, 0.0, mergedImage)
        }

        val finalImage = if (shouldMerge) mergedImage else colorized
        val bmp: Bitmap? = imageTools.matToBitmap(finalImage)
        runOnUiThread { cameraOverlay?.setImageBitmap(bmp) }
    }

    private fun toggleControls() {
        val show = binding.topBar.visibility != View.VISIBLE
        binding.topBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.text = msg
        binding.tvStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.tvStatus.visibility = View.GONE
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class ScaleListener(private val views: List<View>) :
        ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var scaleFactor = 1.0f

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f))
            for (v in views) {
                v.scaleX = scaleFactor
                v.scaleY = scaleFactor
            }
            return true
        }
    }

    private inner class PanListener(private val views: List<View>) :
        GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
            for (v in views) {
                val scaledWidth = v.width * v.scaleX
                val scaledHeight = v.height * v.scaleY
                val maxX = (scaledWidth - v.width) / 2f
                val maxY = (scaledHeight - v.height) / 2f
                v.translationX = (v.translationX - dX).coerceIn(-maxX, maxX)
                v.translationY = (v.translationY - dY).coerceIn(-maxY, maxY)
            }
            return true
        }
    }

    companion object {
        private const val TAG = "UvcMonitor"
    }
}
