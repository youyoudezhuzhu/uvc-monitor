package com.honksoft.monmon

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.honksoft.monmon.databinding.ActivityMainBinding

/**
 * 入口：列出所有 USB UVC 设备（USB 摄像头 / HDMI 采集卡）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var uvcStrategy: CameraUvcStrategy? = null

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = REQUIRED_PERMISSIONS.all { permissions[it] == true }
            if (allGranted) {
                refreshDevices()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshDevices()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uvcStrategy = CameraUvcStrategy(this)

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.btnRefresh.setOnClickListener { refreshDevices() }
        binding.btnRefreshEmpty.setOnClickListener { refreshDevices() }
        binding.tvVersion.setOnClickListener { showAbout() }

        registerReceiver(
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        uvcStrategy?.unRegister()
        uvcStrategy = null
        super.onDestroy()
    }

    private fun refreshDevices() {
        if (!allPermissionsGranted()) {
            requestPermissions()
            return
        }
        val list = uvcStrategy?.getUsbDeviceList() ?: emptyList()
        if (list.isEmpty()) {
            binding.rvDevices.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.tvDeviceCount.text = getString(R.string.empty_title)
        } else {
            binding.emptyState.visibility = View.GONE
            binding.rvDevices.visibility = View.VISIBLE
            binding.tvDeviceCount.text = getString(R.string.device_count, list.size)
            binding.rvDevices.adapter = DeviceAdapter(list) { device ->
                openPreview(device)
            }
        }
    }

    private fun openPreview(device: UsbDevice) {
        val intent = Intent(this, FullscreenActivity::class.java)
        intent.putExtra(EXTRA_DEVICE_NAME, deviceName(device))
        intent.putExtra(EXTRA_DEVICE_ID, device.deviceId)
        startActivity(intent)
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(R.string.about_message)
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_ID = "extra_device_id"

        fun deviceName(device: UsbDevice): String =
            device.productName?.ifBlank { "UVC 设备" } ?: "UVC 设备"

        val REQUIRED_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
