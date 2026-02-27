package com.emerald.negocon

import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid
import platform.CoreBluetooth.CBUUID

actual fun createServiceFilter(serviceUuid: String): ServiceFilter {
    return ServiceFilter(serviceUuids = listOf(CBUUID.UUIDWithString(serviceUuid)))
}

actual fun uuidToString(uuid: Uuid): String = uuid.UUIDString

actual fun extractManufacturerIds(raw: Any?): Set<Int> {
    val data = raw as? ByteArray ?: return emptySet()
    return parseManufacturerIdsFromBytes(data)
}

private fun parseManufacturerIdsFromBytes(data: ByteArray): Set<Int> {
    if (data.size < 2) return emptySet()
    val manufacturerId = (data[0].toUByte().toInt() or (data[1].toUByte().toInt() shl 8))
    return setOf(manufacturerId)
}
