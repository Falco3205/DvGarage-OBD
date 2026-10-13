package it.dvgarage.obd.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import it.dvgarage.obd.AdapterChoice
import it.dvgarage.obd.core.ObdTransport
import it.dvgarage.obd.core.TracingTransport
import it.dvgarage.obd.service.TraceLog

@SuppressLint("MissingPermission")
class Adapters(private val context: Context) {
    private val bluetooth: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val isEnabled: Boolean get() = bluetooth?.isEnabled == true

    fun paired(): List<AdapterChoice> =
        bluetooth?.bondedDevices.orEmpty()
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
            .map { AdapterChoice(it.name ?: it.address, it.address, ble = false) }
            .sortedBy { it.name }

    fun scanBle(durationMs: Long, onFound: (AdapterChoice) -> Unit, onFinished: () -> Unit) {
        val scanner = bluetooth?.bluetoothLeScanner
        if (scanner == null) {
            onFinished()
            return
        }
        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (seen.add(result.device.address)) {
                    onFound(AdapterChoice(name, result.device.address, ble = true))
                }
            }
        }
        scanner.startScan(callback)
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            onFinished()
        }, durationMs)
    }

    fun createTransport(choice: AdapterChoice): ObdTransport {
        val adapter = bluetooth ?: error("Bluetooth is not available on this device")
        val device = adapter.getRemoteDevice(choice.address)
        val transport = if (choice.ble) BleTransport(context, device) else ClassicTransport.create(adapter, device)
        return TracingTransport(transport, TraceLog::add)
    }
}
