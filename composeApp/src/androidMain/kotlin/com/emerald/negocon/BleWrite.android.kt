package com.emerald.negocon

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
