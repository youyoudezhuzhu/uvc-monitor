package com.honksoft.monmon

import android.hardware.usb.UsbDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.honksoft.monmon.databinding.ItemDeviceBinding

/**
 * USB 设备列表适配器
 */
class DeviceAdapter(
    private val devices: List<UsbDevice>,
    private val onClick: (UsbDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun getItemCount(): Int = devices.size

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.binding.tvDeviceName.text = MainActivity.deviceName(device)
        holder.binding.tvDeviceInfo.text =
            "VID:%04X · PID:%04X".format(device.vendorId, device.productId)
        holder.binding.root.setOnClickListener { onClick(device) }
    }
}
