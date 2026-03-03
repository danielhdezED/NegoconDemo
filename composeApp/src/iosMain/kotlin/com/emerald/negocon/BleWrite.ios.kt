package com.emerald.negocon

import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse

actual fun writeBleCommand(
    blueFalcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    data: ByteArray
): Boolean {
    val properties = characteristic.characteristic.properties
    val canWrite = (properties and CBCharacteristicPropertyWrite) != 0u
    val canWriteWithoutResponse = (properties and CBCharacteristicPropertyWriteWithoutResponse) != 0u
    if (!canWrite && !canWriteWithoutResponse) {
        println("Write: not permitted for ${characteristic.uuid}")
        return false
    }
    val writeType = if (canWriteWithoutResponse) 1 else null
    blueFalcon.writeCharacteristicWithoutEncoding(peripheral, characteristic, data, writeType)
    return true
}
