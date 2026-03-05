package com.emerald.negocon

import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral

expect fun writeBleCommand(
    blueFalcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    data: ByteArray
): Boolean

expect fun isCharacteristicWritable(characteristic: BluetoothCharacteristic): Boolean
