package it.dvgarage.obd.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import it.dvgarage.obd.core.ObdTransport
import it.dvgarage.obd.core.StreamTransport
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
object ClassicTransport {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun create(adapter: BluetoothAdapter, device: BluetoothDevice): ObdTransport {
        var socket: BluetoothSocket? = null
        return StreamTransport(
            connect = {
                val connected = connect(adapter, device)
                socket = connected
                connected.inputStream to connected.outputStream
            },
            disconnect = { runCatching { socket?.close() } },
        )
    }

    private fun connect(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket {
        adapter.cancelDiscovery()
        val factories = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            { device.createRfcommSocketToServiceRecord(SPP) },
            { rfcommChannelOne(device) },
        )
        var failure: IOException? = null
        for (factory in factories) {
            val candidate = runCatching { factory() }.getOrNull() ?: continue
            try {
                candidate.connect()
                return candidate
            } catch (e: IOException) {
                failure = e
                runCatching { candidate.close() }
            }
        }
        throw failure ?: IOException("Unable to open a Bluetooth socket")
    }

    private fun rfcommChannelOne(device: BluetoothDevice): BluetoothSocket =
        device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, 1) as BluetoothSocket
}
