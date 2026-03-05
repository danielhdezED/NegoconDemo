@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.emerald.negocon

import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid

object BleUuids {
    const val targetDeviceName = "negocon-ble"
    const val serviceUuid = "d973f2e0-b19e-4f7c-a1a2-1c6e7f0c0a01"
    const val rxUuid = "d973f2e0-b19e-4f7c-a1a2-1c6e7f0c0a02"
    const val txUuid = "d973f2e0-b19e-4f7c-a1a2-1c6e7f0c0a03"
}

expect fun createServiceFilter(serviceUuid: String): ServiceFilter

expect fun uuidToString(uuid: Uuid): String
