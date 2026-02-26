package com.emerald.negocon

import android.os.ParcelUuid
import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid
import kotlin.uuid.ExperimentalUuidApi

actual fun createServiceFilter(serviceUuid: String): ServiceFilter {
    return ServiceFilter(serviceUuids = listOf(ParcelUuid.fromString(serviceUuid)))
}

@OptIn(ExperimentalUuidApi::class)
actual fun uuidToString(uuid: Uuid): String = uuid.toString()
