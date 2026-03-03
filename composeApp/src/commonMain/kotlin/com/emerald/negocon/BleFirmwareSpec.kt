package com.emerald.negocon

enum class FirmwareProfile {
    FW_1_26,
    FW_1_36_2_42,
    FW_6_0,
    UNKNOWN
}

enum class BufferType {
    Events,
    Measurements,
    Alarms,
    Temporal
}

data class DeviceSpec(
    val profile: FirmwareProfile,
    val serviceUuid: String,
    val headerUuid: String,
    val writeUuid: String,
    val readNotifyUuid: String,
    val bufferOrder: List<BufferType>,
    val bufferIds: Map<BufferType, String>,
    val frameSizes: Map<BufferType, Int>,
    val batchSize: Int = 1,
    val notifyTimeoutMs: Long = 1500
)

object SpecRegistry {
    fun specFor(profile: FirmwareProfile): DeviceSpec? {
        return when (profile) {
            FirmwareProfile.FW_1_26 -> DeviceSpec(
                profile = profile,
                serviceUuid = "00040000-0000-1000-8000-00805f9baaaa",
                headerUuid = "00040001-0000-1000-8000-00805f9baaaa",
                writeUuid = "00040002-0000-1000-8000-00805f9baaaa",
                readNotifyUuid = "00040003-0000-1000-8000-00805f9baaaa",
                bufferOrder = listOf(BufferType.Measurements, BufferType.Events),
                bufferIds = mapOf(
                    BufferType.Events to "0001",
                    BufferType.Measurements to "0003"
                ),
                frameSizes = mapOf(
                    BufferType.Events to 6,
                    BufferType.Measurements to 15
                )
            )
            FirmwareProfile.FW_1_36_2_42 -> DeviceSpec(
                profile = profile,
                serviceUuid = "00070000-0000-1000-8000-00805f9baaaa",
                headerUuid = "00070001-0000-1000-8000-00805f9baaaa",
                writeUuid = "00070002-0000-1000-8000-00805f9baaaa",
                readNotifyUuid = "00070002-0000-1000-8000-00805f9baaaa",
                bufferOrder = listOf(BufferType.Events, BufferType.Measurements),
                bufferIds = mapOf(
                    BufferType.Events to "8006",
                    BufferType.Measurements to "8008"
                ),
                frameSizes = mapOf(
                    BufferType.Events to 11,
                    BufferType.Measurements to 9
                )
            )
            FirmwareProfile.FW_6_0 -> DeviceSpec(
                profile = profile,
                serviceUuid = "00070000-0000-1000-8000-00805f9baaaa",
                headerUuid = "00070001-0000-1000-8000-00805f9baaaa",
                writeUuid = "00070002-0000-1000-8000-00805f9baaaa",
                readNotifyUuid = "00070002-0000-1000-8000-00805f9baaaa",
                bufferOrder = listOf(
                    BufferType.Events,
                    BufferType.Measurements,
                    BufferType.Alarms,
                    BufferType.Temporal
                ),
                bufferIds = mapOf(
                    BufferType.Events to "8015",
                    BufferType.Measurements to "8017",
                    BufferType.Alarms to "8016",
                    BufferType.Temporal to "8018"
                ),
                frameSizes = mapOf(
                    BufferType.Events to 15,
                    BufferType.Measurements to 12,
                    BufferType.Alarms to 15,
                    BufferType.Temporal to 34
                )
            )
            FirmwareProfile.UNKNOWN -> null
        }
    }
}
