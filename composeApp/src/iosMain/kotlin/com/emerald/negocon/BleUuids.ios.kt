package com.emerald.negocon

import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid
import platform.CoreBluetooth.CBUUID

actual fun createServiceFilter(serviceUuid: String): ServiceFilter {
    return ServiceFilter(serviceUuids = listOf(CBUUID.UUIDWithString(serviceUuid)))
}

actual fun uuidToString(uuid: Uuid): String = uuid.UUIDString
