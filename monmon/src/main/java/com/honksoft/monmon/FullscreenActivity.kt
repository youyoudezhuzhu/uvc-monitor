package com.honksoft.monmon

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.honksoft.monmon.MainActivity.Companion.EXTRA_DEVICE_ID
import com.honksoft.monmon.MainActivity.Companion.EXTRA_DEVICE_NAME
import com.honksoft.monmon.MainActivity.Companion.REQUIRED_PERMISSIONS
import com.honksoft.monmon.databinding.ActivityFullscreenBinding
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.widget.AspectRatioSurfaceView
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * 全屏实时预览：USB 摄像头 / HDMI 采集卡
 * 支持：峰焦辅助（OpenCV 边缘叠加）、分辨率选择、画面旋转、捏合缩放、点按唤出控制条
 */
class FullscreenActivity : AppCompatActivity() {

    private var cameraHelper: ICameraHelper? = null
    private lateinit var binding: ActivityFullscreenBinding
    private lateinit var imageTools: ImageTools

    private var cameraViewMain: AspectRatioSurfaceView? = null
    private var cameraOverlay: ImageView? = null

    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var panGestureDetector: GestureDetector? = null
    private var isPinching = false

    private var rotationDeg = 0
    private var frameCallbackRegistered = false
    private var currentSize: Size? = null

    @Volatile
    private var peakEnabled = true
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
                initCameraHelper()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
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
        cameraViewMain?.setAspectRatio(640, 480)

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
        binding.btnPeak.setOnClickListener {
            peakEnabled = !peakEnabled
            applyPeakMode()
        }
        binding.btnRotate.setOnClickListener {
            rotationDeg = (rotationDeg + 90) % 360
            applyRotation()
        }
        binding.btnResolution.setOnClickListener { showResolutionDialog() }
        binding.btnSettings.setOnClickListener {
            ViewSettingsFragment().show(supportFragmentManager, "view_settings_dialog")
        }

        binding.tvTitle.text = intent.getStringExtra(EXTRA_DEVICE_NAME)
            ?: getString(R.string.app_name)

        cameraViewMain?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                cameraHelper?.addSurface(holder.getSurface(), false)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraHelper?.removeSurface(holder.getSurface())
            }
        })

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
        initCameraHelper()
    }

    override fun onStop() {
        super.onStop()
        clearCameraHelper()
    }

    private fun initCameraHelper() {
        if (!allPermissionsGranted()) {
            requestPermissions()
            return
        }
        if (cameraHelper == null) {
            cameraHelper = CameraHelper()
            cameraHelper?.setStateCallback(stateListener)

            val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
            val list: MutableList<UsbDevice?>? = cameraHelper?.getDeviceList()
            if (list != null && list.isNotEmpty()) {
                val target = list.find { it?.deviceId == deviceId } ?: list.first()
                val name = MainActivity.deviceName(target ?: return)
                showStatus(getString(R.string.device_connecting, name))
                cameraHelper?.selectDevice(target)
            } else {
                showStatus(getString(R.string.no_device))
            }
        }
    }

    private fun clearCameraHelper() {
        unregisterFrameCallback()
        cameraHelper?.stopPreview()
        cameraHelper?.release()
        cameraHelper = null
    }

    private val stateListener: ICameraHelper.StateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
        }

        override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice?) {
            val sizes = cameraHelper?.supportedSizeList
            if (sizes.isNullOrEmpty()) {
                showStatus(getString(R.string.open_failed))
                return
            }
            hideStatus()
            currentSize = pickDefaultSize(sizes)
            applyPreviewSize()
            applyPeakMode()
        }

        override fun onCameraClose(device: UsbDevice?) {
            unregisterFrameCallback()
            cameraHelper?.stopPreview()
        }

        override fun onDeviceClose(device: UsbDevice?) {
        }

        override fun onDetach(device: UsbDevice?) {
        }

        override fun onCancel(device: UsbDevice?) {
        }
    }

    private fun pickDefaultSize(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) return null
        return sizes.find { it.height == 1080 } ?: sizes.maxByOrNull { it.width * it.height }
    }

    private fun applyPreviewSize() {
        val size = currentSize ?: return
        cameraHelper?.stopPreview()
        cameraHelper?.previewSize = size
        cameraViewMain?.setAspectRatio(size.width, size.height)
        binding.tvResolution.text = "${size.width}×${size.height}"
        cameraHelper?.startPreview()
    }

    /** 峰焦开：帧处理 overlay；峰焦关：SurfaceView 直通（更流畅） */
    private fun applyPeakMode() {
        updatePeakButton()
        if (peakEnabled) {
            cameraViewMain?.visibility = View.GONE
            cameraOverlay?.visibility = View.VISIBLE
            registerFrameCallback()
        } else {
            cameraOverlay?.visibility = View.GONE
            cameraViewMain?.visibility = View.VISIBLE
            unregisterFrameCallback()
        }
    }

    private fun updatePeakButton() {
        val tint = ContextCompat.getColor(
            this,
            if (peakEnabled) R.color.secondary else R.color.text_tertiary
        )
        binding.btnPeak.imageTintList = ColorStateList.valueOf(tint)
    }

    private fun registerFrameCallback() {
        if (frameCallbackRegistered) return
        frameCallbackRegistered = true
        cameraHelper?.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
    }

    private fun unregisterFrameCallback() {
        if (!frameCallbackRegistered) return
        frameCallbackRegistered = false
        cameraHelper?.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_NV21)
    }

    private val frameCallback = IFrameCallback { frame: ByteBuffer? ->
        val nv21 = ByteArray(frame!!.remaining())
        frame.get(nv21, 0, nv21.size)
        val size: Size = cameraHelper?.getPreviewSize() ?: return@IFrameCallback

        if (dontEdge) {
            val bmp: Bitmap = imageTools.nv21ToBitmap(nv21, size.width, size.height)
            runOnUiThread { cameraOverlay?.setImageBitmap(bmp) }
            return@IFrameCallback
        }

        // NV21 -> RGB
        var yuv = Mat(size.height + size.height / 2, size.width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        var rgba = Mat(size.width, size.height, CvType.CV_8UC1)
        Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGB_NV21, 4)

        val imgGray = Mat()
        val cannyEdges = Mat()
        val colorized = Mat()
        val mergedImage = Mat()

        // 灰度 + 高斯模糊
        Imgproc.cvtColor(rgba, imgGray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(imgGray, imgGray, org.opencv.core.Size(5.0, 5.0), 6.0, 6.0)

        // 边缘检测（阈值来自设置）
        val mappedThreshold = (threshold.toFloat()).map(0.0f, 100.0f, -4.0f, 1.5f)
        Imgproc.Canny(imgGray, cannyEdges, 80.0 * -mappedThreshold, 100.0 * -mappedThreshold)
        Imgproc.cvtColor(cannyEdges, colorized, Imgproc.COLOR_GRAY2RGBA)

        // 染色
        val mappedColor = when (PrefsPeakColorType.entries[color]) {
            PrefsPeakColorType.RED -> Scalar(5.0, 0.0, 0.0)
            PrefsPeakColorType.GREEN -> Scalar(0.0, 5.0, 0.0)
            PrefsPeakColorType.BLUE -> Scalar(0.0, 0.0, 5.0)
            PrefsPeakColorType.YELLOW -> Scalar(5.0, 5.0, 0.0)
            PrefsPeakColorType.WHITE -> Scalar(1.0, 1.0, 1.0)
        }
        Core.multiply(colorized, mappedColor, colorized)

        // 加粗边缘
        val kernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(5.0, 5.0))
        Imgproc.dilate(colorized, colorized, kernel, Point(-1.0, -1.0), 1)

        // 叠加原图
        if (shouldMerge) {
            Core.addWeighted(rgba, 1.0, colorized, 0.7, 0.0, mergedImage)
        }

        val finalImage = if (shouldMerge) mergedImage else colorized
        val bmp: Bitmap? = imageTools.matToBitmap(finalImage)
        runOnUiThread { cameraOverlay?.setImageBitmap(bmp) }
    }

    private fun showResolutionDialog() {
        val sizes = cameraHelper?.supportedSizeList ?: return
        if (sizes.isEmpty()) return
        val labels = sizes.map { s ->
            val fps = s.fpsList?.firstOrNull() ?: s.fps
            "${s.width}×${s.height}  ${fps}fps"
        }
        val currentIndex = sizes.indexOfFirst {
            it.width == currentSize?.width && it.height == currentSize?.height
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.resolution_title)
            .setSingleChoiceItems(labels.toTypedArray(), currentIndex) { d, which ->
                currentSize = sizes[which]
                applyPreviewSize()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun applyRotation() {
        val deg = rotationDeg.toFloat()
        for (v in listOf(cameraViewMain, cameraOverlay)) {
            v?.pivotX = (v?.width ?: 0) / 2f
            v?.pivotY = (v?.height ?: 0) / 2f
            v?.rotation = deg
        }
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
