@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.emerald.negocon

import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid

object BleUuids {
    const val targetService = "00010000-0000-1000-8000-00805F9BAAAA"
    const val targetCharacteristic = "00010002-0000-1000-8000-00805F9BAAAA"
}

expect fun createServiceFilter(serviceUuid: String): ServiceFilter

expect fun uuidToString(uuid: Uuid): String
