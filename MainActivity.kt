package com.pranay.bleapplication


import android.Manifest import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController


import com.pranay.bleapplication.ui.theme.BLEApplicationTheme
import android.os.Parcelable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*


import androidx.compose.foundation.layout.fillMaxWidth import androidx.compose.material3.ButtonDefaults import androidx.compose.material3.MaterialTheme import androidx.compose.ui.Alignment import androidx.compose.ui.graphics.Color import kotlinx.parcelize.Parceler import kotlinx.parcelize.Parcelize import androidx.lifecycle.LiveData import androidx.lifecycle.MutableLiveData import androidx.lifecycle.ViewModel import androidx.lifecycle.ViewModelProvider import android.app.Activity
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID


//import com.pranay.bleapplication.ui.DeviceDetail


@Suppress("unused") class BluetoothViewModel : ViewModel(){ private val _scannedDevices = MutableLiveData<List<Device>>(emptyList())
    val scannedDevices: LiveData<List<Device>> = _scannedDevices

    fun updateScannedDevices(devices: List<Device>){
        _scannedDevices.postValue(devices)
    }
} @Parcelize data class Device(
    val name: String?,
    val address: String?,
    var connected: Boolean = false,
    var rssi: Int = 0,
    val genericAttributeUUID: UUID? = null,
    val genericAccessUUID: UUID? = null ) : Parcelable {
    var batteryLevel: Int? = null //battery indicator
    constructor(parcel: Parcel) : this( parcel.readString(), parcel.readString()
    )




    companion object : Parceler<Device> {


        override fun Device.write(parcel: Parcel, flags: Int) {
            parcel.writeString(name)
            parcel.writeString(address)
        }


        override fun create(parcel: Parcel): Device {
            return Device(parcel)
        }
    }
}


class MainActivity : ComponentActivity() {

    // private var devices by  mutableStateOf<List<String>>(emptyList())
    private val _devices = MutableLiveData<List<Device>>(emptyList())
    private val devices: LiveData<List<Device>> = _devices


    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 2
        private const val REQUEST_ENABLE_BLUETOOTH = 3
        private const val REQUEST_BLUETOOTH_CONNECT = 4
        private const val TAG = "MainActivity"
        private const val SCAN_PERIOD: Long = 10000 // 10 seconds
    }


    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scannedDevices = mutableStateListOf<String>()
    private var discoverableDevices = mutableStateListOf<String>()
    private var isScanning = false
    private lateinit var navController: NavController


    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            BLEApplicationTheme {
                navController = rememberNavController()
                NavHost(navController as NavHostController, startDestination = Screen.Home.route) {
                    composable(Screen.Home.route) {
                        Greeting(name = "Pranay", navController = navController)
                    }
                    composable(Screen.ScanResults.route) {
                        devices.value?.let { it1 ->
                            ScanResultsScreen(navController = navController, devices = it1, onConnect = {device ->
                                connectToDevice(device)
                                navController.navigate(Screen.DeviceDetail.route + "/${device.address}")
                            })
                        }
                    }
                    composable(Screen.DeviceDetail.route + "/{deviceId}"){ backStackEntry ->
                        val deviceId = backStackEntry.arguments?.getString("deviceId")
                        val device = _devices.value?.find { it.address == deviceId }
                        device?.let{
                            DeviceDetailScreen(device = it, navController = navController)
                        } ?: run{
                            Text(text = "Device not found")
                        }
                    }
                }
            }
        }



        // Initialize Bluetooth adapter and scanner
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothAdapter?.bluetoothLeScanner.also { bluetoothLeScanner = it }


        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                checkBluetoothPermissions()
            } else {
                displayRationale()
            }
        }


        bluetoothPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                checkBluetoothEnabled()
            } else {
                Toast.makeText(this, R.string.bluetooth_permissions_not_granted, Toast.LENGTH_SHORT).show()
            }
        }


        // Check for location and Bluetooth permissions on app launch
        checkPermissions()
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkPermissions() {
        Log.d(TAG, "Checking location and Bluetooth permissions")
        if (isAboveMarshmallow() && !isLocationPermissionEnabled()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else if (!isLocationEnabled()) {
            promptEnableLocation()
        } else {
            checkBluetoothPermissions()
        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkBluetoothPermissions() {
        Log.d(TAG, "Checking Bluetooth permissions")
        if (isBluetoothPermissionEnabled()) {
            checkBluetoothEnabled()
        } else {
            val requiredPermissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (requiredPermissions.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(requiredPermissions.toTypedArray())
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkBluetoothEnabled() {
        Log.d(TAG, "Checking if Bluetooth is enabled")
        if (!isBluetoothEnabled()) {
            requestBluetoothEnable()
        } else {
            initBLEModule()
        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothEnable() {
        Log.d(TAG, "Requesting user to enable Bluetooth")
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun displayRationale() {
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (shouldShowRationale) {
            Log.i(TAG, "Showing rationale for location permission")
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.location_permission_disabled))
                .setPositiveButton(getString(R.string.ok)) { _, _ -> checkPermissions() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else {
            Log.i(TAG, "Prompting user to settings page for location permission")
            promptSettingsPage()
        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (!isLocationEnabled()) {
                        promptEnableLocation()
                    } else {
                        checkBluetoothPermissions()
                    }
                } else {
                    Log.w(TAG, "Location permission denied")
                    displayRationale()
                }
            }
            REQUEST_BLUETOOTH_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    if (!isLocationEnabled()) {
                        promptEnableLocation()
                    } else {
                        checkBluetoothEnabled()
                    }
                } else {
                    Log.w(TAG, "Bluetooth permissions not granted")
                    Toast.makeText(this, R.string.bluetooth_permissions_not_granted, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_ENABLE_BLUETOOTH -> {
                if (resultCode == RESULT_OK) {
                    initBLEModule()
                } else {
                    Toast.makeText(this, R.string.bluetooth_not_enabled, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun isLocationPermissionEnabled(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }


    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }


    private fun promptEnableLocation() {
        AlertDialog.Builder(this)
            .setMessage("Location services are required for BLE scanning. Please enable location services.")
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }


    private fun isBluetoothPermissionEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }


    private fun isAboveMarshmallow(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }


    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled ?: false
    }


    private fun initBLEModule() {
        Log.d(TAG, "Initializing BLE module")
        Toast.makeText(this, "BLE Module Initialized", Toast.LENGTH_SHORT).show()
        startScanning()
    }


    private fun startScanning() {
        Log.d(TAG, "Starting the scan")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Bluetooth scan permission not granted, returning early")
            bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
            return
        }


        isScanning = true
        scannedDevices.clear()
        discoverableDevices.clear()


        // Start BLE scan
        bluetoothLeScanner?.startScan(leScanCallback)
        Log.i(TAG, "Started Scanning for BLE Devices")
        Toast.makeText(this, "Started Scanning for BLE Devices", Toast.LENGTH_SHORT).show()


        // Start classic Bluetooth discovery
        bluetoothAdapter?.startDiscovery()
        Log.i(TAG, "Started Discovery for Classic Bluetooth Devices")
        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))


        Handler(Looper.getMainLooper()).postDelayed({
            stopScanning()
            updateUI(discoverableDevices + scannedDevices)
        }, SCAN_PERIOD)
    }


    private fun updateUI(strings: List<String>) {

    }


    private fun stopScanning() {
        if (isScanning) {
            isScanning = false
            Log.d(TAG, "Stopping the scan")
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.i(TAG, "Stopped BLE Scan")
            bluetoothAdapter?.cancelDiscovery()
            Log.i(TAG, "Cancelled Classic Bluetooth Discovery")
            unregisterReceiver(receiver)
        }
    }

    private fun promptSettingsPage() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.prompt_settings_page))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri: Uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }


    private val leScanCallback = object : ScanCallback() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device = result?.device
            val deviceInfo = "${device?.name?:"Unknown Device"} - ${device?.address?: "00:01:02:03:04:05"} - RSSI: ${result?.rssi} dBm"
            val genericAttributeUUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
            val genericAccessUUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
            Log.d(TAG, "BLE Device found: $deviceInfo")


            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Bluetooth connect permission not granted")
                bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                return
            }


            //val deviceInfo = "${device?.name ?: "Unknown Device"} - ${device?.address ?: "00:01:02:03:04:05"}"
            Log.d(TAG, "BLE Device found: $deviceInfo")


            // update LiveData with new device
            val currentDevices = _devices.value?.toMutableList()?: mutableListOf()
            if(!currentDevices.any{it.address == device?.address}){
                currentDevices.add(Device(device?.name, device?.address, rssi = result?.rssi ?: 0))
                _devices.postValue(currentDevices)
            }


            if (!scannedDevices.contains(deviceInfo)) {
                scannedDevices.add(deviceInfo)
                updateUI(scannedDevices.toList())
                Log.i(TAG, "Added BLE Device: $deviceInfo")


                result?.let {
                    val logMessage = """
                       onScanResult() - 
                   addressType=${it.scanRecord?.deviceName},
                   address=${it.device.address},
                   primaryPhy=${it.primaryPhy},
                   secondaryPhy=${it.secondaryPhy},
                   advertisingSid=${it.advertisingSid},
                   txPower=${it.txPower},
                   rssi=${it.rssi},
                   periodicAdvInt=${it.periodicAdvertisingInterval},
                   originalAddress=${it.device.address},
                   type=${it.scanRecord?.advertiseFlags},
                   channel=${it.scanRecord?.txPowerLevel}
                   """.trimIndent()
                    Log.d(TAG, logMessage)
                }
            }
        }
    }


    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // Handle permission request here, or simply return if you cannot proceed
                    Log.w(TAG, "Bluetooth connect permission not granted")
                    return
                }
                val deviceInfo = "${device?.name ?: "Unknown Device"} - ${device?.address ?: "00:01:02:03:04:05"} - RSSI: ${device?.bluetoothClass?.deviceClass ?: 0}"
                val genericAttributeUUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb") // Generic Attribute UUID
                val genericAccessUUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb") // Generic Access UUID
                Log.d(TAG, "Classic Bluetooth device found: $deviceInfo")


                val viewModel = ViewModelProvider(this@MainActivity).get(BluetoothViewModel::class.java)
                viewModel.updateScannedDevices(listOf(Device(device?.name, device?.address)))


                // Update LiveData with new device
                val currentDevices = _devices.value?.toMutableList() ?: mutableListOf()
                if (!currentDevices.any { it.address == device?.address }) {
                    currentDevices.add(Device(device?.name, device?.address, rssi = device?.bluetoothClass?.deviceClass?: 0))
                    _devices.postValue(currentDevices)
                    /*if (!discoverableDevices.contains(deviceInfo)) {
                        discoverableDevices.add(deviceInfo)
                        updateUI(discoverableDevices + scannedDevices)
                        Log.i(TAG, "Added Classic Bluetooth Device: $deviceInfo")*/


                }
            }
        }
    }


    @Composable
    fun Greeting(name: String, navController: NavController) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column (
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start
            ){
                Text(
                    text = "Hello $name welcome to the app",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { navController.navigate(Screen.ScanResults.route) },
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text("Start Scan")
                }
            }
        }
    }


    @Composable
    fun ScanResultsScreen(
        navController: NavController,
        devices: List<Device>,
        onConnect: (Device) -> Unit
    ) {
        Column {
            Text(
                text = "Scan results",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )
            LazyColumn {
                items(devices.filter { it.name == "Coolzen" }) { device ->
                    DeviceItem(device = device, onConnect = onConnect)
                }
            }
        }
    }


    @Composable
    fun DeviceItem(device: Device, onConnect: (Device) -> Unit) {
        val rssiPercentage = (device.rssi / -100.0) * 100
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically

        ){
            Column(modifier = Modifier.padding(8.dp)) {
                Text(text = device.name ?: "Unknown Device")
                Text(text = device.address ?: "Unknown Address")
                Text(text = "RSSI: ${device.rssi}")
                Text(text = "Generic Attribute UUID: ${device.genericAttributeUUID}")
                Text(text = "Generic Access UUID: ${device.genericAccessUUID}")
                Text(text = "Battery Level: ${device.batteryLevel?.let { "$it%" } ?: " "}")
            }
            Button(
                onClick = { onConnect(device) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (device.connected) Color.Green else Color.Yellow,
                    contentColor = Color.Black // Text Color
                ),
                modifier = Modifier
                    .padding(8.dp)
                    .width(IntrinsicSize.Max)
            ) {
                Text(if (device.connected) "Connected" else "Connect")
            }
        }
    }


    private val connectedDeviceCallbacks = mutableMapOf<String, BluetoothGattCallback>()


    private fun connectToDevice(device: Device){
        if(ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ){
            // Request necessary permissions from the user
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT
            )
            return
        }


        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)


        val gattCallback = object : BluetoothGattCallback(){
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                when(newState){
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to GATT Server")

                        runOnUiThread {
                            device.connected = true
                            _devices.postValue(_devices.value)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED ->{
                        Log.i(TAG,"Disconnected from GATT Server")
                        runOnUiThread {
                            device.connected = false
                            _devices.postValue(_devices.value)
                            if(ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED){
                                gatt?.close()
                            } else {
                                Log.w(TAG, "BLUETOOTH_CONNECT permission denied")
                            }
                        }
                    }
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if(status == BluetoothGatt.GATT_SUCCESS){
                    Log.i(TAG, "Services Discovered")

                    // Define the UUIDs for the battery service and characteristic
                    val batteryServiceUUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
                    val batteryLevelUUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

                    // Get the battery service and characteristic
                    val batteryService = gatt?.getService(batteryServiceUUID)
                    val batteryLevelCharacteristic = batteryService?.getCharacteristic(batteryLevelUUID)
                    if (batteryLevelCharacteristic != null) {
                        if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                            try {
                                gatt?.readCharacteristic(batteryLevelCharacteristic)
                            } catch (e: SecurityException){
                                Log.e(TAG, "Error reading charecteristic: $e")
                            }
                        } else {
                            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted")

                            requestPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                REQUEST_PERMISSION_CODE
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "onServiceDiscovered received: $status")
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, value, status)
                if(status == BluetoothGatt.GATT_SUCCESS){
                    val batteryLevelUUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
                    if (characteristic?.uuid == batteryLevelUUID){
                        val batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                        Log.i(TAG, "Battery level: $batteryLevel %")
                        // Update the device's battery level
                        val device = _devices.value?.find { it.address == gatt?.device?.address }
                        device?.batteryLevel = batteryLevel
                        _devices.postValue(_devices.value)
                    }
                    else {
                        Log.w(TAG, "OnCharacteristicRead received: $status")
                    }
                }
            }
        }


        if(device.connected){
            // Disconnect from the device
            connectedDeviceCallbacks.remove(device.address)?.let { callback ->
                bluetoothDevice?.connectGatt(this, false, callback)?.disconnect()
            }
            bluetoothDevice?.connectGatt(this, false, object : BluetoothGattCallback(){
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt?,
                    status: Int,
                    newState: Int
                ) {
                    super.onConnectionStateChange(gatt, status, newState)
                    if(newState == BluetoothProfile.STATE_DISCONNECTED){
                        Log.i(TAG, "Disconnected from GATT server")
                        runOnUiThread{
                            device.connected = false
                            _devices.postValue(_devices.value)
                            //Navigate to device details screen


                        }
                    }
                }
            })?.disconnect()
        } else {
            // Connect to the device
            bluetoothDevice?.connectGatt(this, false, object : BluetoothGattCallback(){
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt?,
                    status: Int,
                    newState: Int
                ) {
                    super.onConnectionStateChange(gatt, status, newState)
                    when(newState){
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Connected to GATT server")
                            runOnUiThread {
                                device.connected = true
                                _devices.postValue(_devices.value)


                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED ->{
                            Log.i(TAG, "Disconnected from GATT server")
                            runOnUiThread {
                                device.connected = false
                                _devices.postValue(_devices.value)
                            }
                        }
                    }
                }

            })
        }
    }


    @Composable
    fun DeviceDetailScreen(device: Device, navController: NavController){
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top
            ){
                Button(
                    onClick = {
                        disconnectDevice(device)
                        navController.popBackStack() // Navigate back to the previous screen
                    },
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(text = "Disconnect")
                }
            }
            Text(text = "Device Details", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Name: ${device.name ?: "Unknown"}")
            Text(text = "Address: ${device.address ?: "Unknown"}")
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { navController.popBackStack() }, // Navigate back to previous screen
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Back")
            }
        }


        fun onRequestPermissionResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray){
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            if(requestCode == REQUEST_BLUETOOTH_CONNECT && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                // Handle permission granted
                Log.d(TAG, "Bluetooth connect permission granted")
                // Proceed with connecting to the device or any other action
            } else {
                Log.w(TAG, "Bluetooth connect permission not granted")
            }
        }


        val gattCallback = object : BluetoothGattCallback(){
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                when(newState){
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to GATT server")
                        // Check if Bluetooth connect permission is granted
                        if(ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)!= PackageManager.PERMISSION_GRANTED){
                            Log.e(TAG, "Bluetooth connect permission not granted")
                            return
                        }
                        gatt?.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server")
                    }
                }
            }


            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if(status == BluetoothGatt.GATT_SUCCESS){
                    Log.i(TAG, "Services Discovered")
                } else {
                    Log.w(TAG, "OnServicesDiscovered received: $status")
                }
            }
        }


        fun updateUI(devices: List<String>) {
            val coolzenDevices = devices.filter { device ->
                val (name, _) = device.split(" - ")
                name == "Coolzen"
            }.map { device ->
                val (name, address) = device.split(" - ")
                Device(name, address)
            }


            Log.d(TAG, "Coolzen Devices found: $coolzenDevices")


            _devices.value = coolzenDevices
        }
    }


    private val REQUEST_PERMISSION_CODE = 1


    private fun disconnectDevice(device: Device) {
        if (device.connected) {
            // Check if the app has BLUETOOTH_CONNECT permission
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                if (bluetoothDevice != null) {
                    val gattCallback = connectedDeviceCallbacks[device.address]
                    if (gattCallback != null) {
                        val gatt = bluetoothDevice.connectGatt(this, false, gattCallback)
                        if (gatt != null) {
                            gatt.disconnect()
                            gatt.close()
                        }
                        // Remove the callback from the map
                        connectedDeviceCallbacks.remove(device.address)
                    } else {
                        Log.w(TAG, "GattCallback is null for device address: ${device.address}")
                    }
                } else {
                    Log.w(TAG, "BluetoothDevice is null for device address: ${device.address}")
                }
                // Update the device connection status
                device.connected = false
                _devices.postValue(_devices.value)
            } else {
                // Request BLUETOOTH_CONNECT permission
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_PERMISSION_CODE)
            }
        } else {
            Log.w(TAG, "Device is not connected")
        }
    }
}


private fun requestPermission(context: Context, permission: String, requestCode: Int){
    if(ContextCompat.checkSelfPermission( context, permission )!= PackageManager.PERMISSION_GRANTED){
        ActivityCompat.requestPermissions((context as Activity), arrayOf(permission), requestCode)
    }
}






sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object ScanResults : Screen("scan_results")
    data object DeviceDetail: Screen("device_detail")
}
