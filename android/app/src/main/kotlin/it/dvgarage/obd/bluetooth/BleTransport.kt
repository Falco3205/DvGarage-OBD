package it.dvgarage.obd.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import it.dvgarage.obd.core.ObdProtocolException
import it.dvgarage.obd.core.PromptTransport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : PromptTransport() {

    private val ready = CountDownLatch(1)
    private val writeAck = Semaphore(0)

    @Volatile private var failure: String? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> fail("Adapter disconnected")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val (notify, write) = pickCharacteristics(g) ?: return fail("No serial characteristic found on the adapter")
            writeCharacteristic = write
            g.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(CCCD) ?: return fail("Notifications not supported by the adapter")
            val enable = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writeDescriptor(g, descriptor, enable)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.countDown() else fail("Could not enable notifications")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeAck.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onData(characteristic.value ?: return)
        }
    }

    override fun open() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            close()
            throw ObdProtocolException("Timed out connecting to the BLE adapter")
        }
        failure?.let {
            close()
            throw ObdProtocolException(it)
        }
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: throw ObdProtocolException("Transport is not open")
        val characteristic = writeCharacteristic ?: throw ObdProtocolException("Transport is not open")
        failure?.let { throw ObdProtocolException(it) }
        for (chunk in data.toList().chunked(CHUNK_SIZE)) {
            writeAck.drainPermits()
            if (!writeChunk(g, characteristic, chunk.toByteArray())) throw ObdProtocolException("BLE write rejected")
            if (characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT &&
                !writeAck.tryAcquire(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ) {
                throw ObdProtocolException("BLE write timed out")
            }
        }
    }

    override fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        writeCharacteristic = null
    }

    private fun fail(message: String) {
        failure = message
        ready.countDown()
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val all = g.services
            .filter { it.uuid !in GENERIC_SERVICES }
            .flatMap { it.characteristics }
        val notify = all.filter { it.properties and NOTIFY_MASK != 0 }
        val write = all.filter { it.properties and WRITE_MASK != 0 }
        val shared = notify.firstOrNull { it in write }
        if (shared != null) return shared to shared
        val n = notify.firstOrNull() ?: return null
        val w = write.firstOrNull() ?: return null
        return n to w
    }

    private fun writeChunk(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        characteristic.writeType = type
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = bytes
            g.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }
        if (!accepted) fail("Could not enable notifications")
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val GENERIC_SERVICES = setOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
        )
        const val NOTIFY_MASK = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        const val WRITE_MASK = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        const val CHUNK_SIZE = 20
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_MS = 2_000L
    }
}
