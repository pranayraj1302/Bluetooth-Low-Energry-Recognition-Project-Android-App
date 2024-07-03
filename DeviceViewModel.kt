package com.pranay.bleapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class DeviceViewModel : ViewModel() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var context: Context? = null

    private val _scannedDevices = MutableStateFlow<List<String>>(emptyList())
    val scannedDevices: StateFlow<List<String>>
        get() = _scannedDevices

    companion object {
        const val TAG = "DeviceViewModel"
        private const val SCAN_PERIOD: Long = 10000 // 10 seconds
    }

    fun initBluetoothAdapter(adapter: BluetoothAdapter?, scanner: BluetoothLeScanner?, context: Context) {
        bluetoothAdapter = adapter
        bluetoothLeScanner = scanner
        this.context = context
    }

    fun startScanning() {
        Log.d(TAG, "Starting the scan")
        if (isScanning) return

        isScanning = true
        if (ContextCompat.checkSelfPermission(
                context ?: return,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Request Bluetooth Scan permission here if needed
            Log.e(TAG, "Bluetooth scan permission not granted")
            return
        }
        try {
            bluetoothLeScanner?.startScan(leScanCallback)

            // Example: Stop scanning after a fixed SCAN_PERIOD
            Handler(Looper.getMainLooper()).postDelayed({
                stopScanning()
            }, SCAN_PERIOD)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start scan due to lack of permissions: ${e.message}")
            isScanning = false
        }
    }

    fun addDevice(deviceInfo: String) {
        val currentList = _scannedDevices.value.toMutableList()
        currentList.add(deviceInfo)
        _scannedDevices.value = currentList
    }

    fun stopScanning() {
        if (!isScanning) return

        isScanning = false

        if (ContextCompat.checkSelfPermission(
                context ?: return,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                Log.d(TAG, "Stopping the scan")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to stop scan due to lack of permissions: ${e.message}")
            }
        } else {
            Log.e(TAG, "Cannot stop scan, no permission granted")
        }
    }



    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device = result?.device

            device?.let {
                context?.let { ctx ->
                    if (ActivityCompat.checkSelfPermission(
                            ctx,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {

                        val deviceInfo =
                            "${device.name ?: "Unknown Device"} - ${formatMacAddress(device.address)}"
                        // Accessing addDevice method of the current instance of DeviceViewModel
                        addDevice(deviceInfo)
                    } else {
                        Log.e(TAG, "Permission not granted to access device info")


                    }
                }
            }
        }
    }

    private fun formatMacAddress(macAddress: String?): String {
        if (macAddress.isNullOrEmpty() || macAddress.length != 12) {
            return "00:00:00:00:00:00"
        }

        val formattedMac = StringBuilder()
        for (i in 0 until 12 step 2) {
            formattedMac.append(macAddress.substring(i, i + 2))
            if (i < 10) {
                formattedMac.append(":")
            }
        }
        return formattedMac.toString()
    }

    override fun onCleared() {
        super.onCleared()
        stopScanning()
    }
}
