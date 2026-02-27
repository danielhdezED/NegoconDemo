package com.emerald.negocon

import android.os.ParcelUuid
import android.util.SparseArray
import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid
import kotlin.uuid.ExperimentalUuidApi
actual fun createServiceFilter(serviceUuid: String): ServiceFilter {
    return ServiceFilter(serviceUuids = listOf(ParcelUuid.fromString(serviceUuid)))
}

@OptIn(ExperimentalUuidApi::class)
actual fun uuidToString(uuid: Uuid): String = uuid.toString()

actual fun extractManufacturerIds(raw: Any?): Set<Int> {
    return when (raw) {
        is SparseArray<*> -> {
            val ids = mutableSetOf<Int>()
            for (index in 0 until raw.size()) {
                ids.add(raw.keyAt(index))
            }
            ids
        }
        is Map<*, *> -> raw.keys.filterIsInstance<Int>().toSet()
        is ByteArray -> parseManufacturerIdsFromBytes(raw)
        else -> emptySet()
    }
}

private fun parseManufacturerIdsFromBytes(data: ByteArray): Set<Int> {
    if (data.size < 2) return emptySet()
    val manufacturerId = (data[0].toUByte().toInt() or (data[1].toUByte().toInt() shl 8))
    return setOf(manufacturerId)
}
