package com.emerald.negocon

import android.bluetooth.BluetoothGattCharacteristic
import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral

actual fun writeBleCommand(
    blueFalcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    data: ByteArray
): Boolean {
    blueFalcon.writeCharacteristicWithoutEncoding(peripheral, characteristic, data, null)
    return true
}

actual fun isCharacteristicWritable(characteristic: BluetoothCharacteristic): Boolean {
    val props = characteristic.characteristic.properties
    val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
    val canWriteNoResponse = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    return canWrite || canWriteNoResponse
}
