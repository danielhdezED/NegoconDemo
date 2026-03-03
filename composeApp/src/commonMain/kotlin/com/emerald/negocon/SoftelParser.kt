package com.emerald.negocon

data class SoftelBuffer(
    val bufferName: String,
    val data: MutableList<ByteArray>
)

data class ReadingLog(
    val type: String,
    val data: List<String>,
    val idController: String,
    val idCooler: String,
    val dateReading: String
)

object SoftelParser {
    fun extractPackets(bytes: ByteArray, frameSize: Int): List<String> {
        if (bytes.isEmpty() || frameSize <= 0) return emptyList()
        val buffer = bytes.toHexString()
        if (buffer.length <= 16) return emptyList()
        val payload = buffer.substring(16)
        val hexFrameSize = frameSize * 2
        if (payload.length < hexFrameSize) return emptyList()
        val packets = ArrayList<String>()
        var index = 0
        while (index + hexFrameSize <= payload.length) {
            val segment = payload.substring(index, index + hexFrameSize)
            if (!isAllZeros(segment)) {
                packets.add(segment)
            }
            index += hexFrameSize
        }
        return packets
    }

    fun buildReadingLogs(
        buffers: Map<BufferType, SoftelBuffer>,
        id: String,
        frameSizes: Map<BufferType, Int>
    ): List<ReadingLog> {
        val logs = ArrayList<ReadingLog>()
        val now = ""
        buffers.forEach { (type, buffer) ->
            val items = ArrayList<String>()
            val frameSize = frameSizes[type] ?: 0
            buffer.data.forEach { items.addAll(extractPackets(it, frameSize)) }
            items.chunked(1000).forEach { chunk ->
                logs.add(
                    ReadingLog(
                        type = type.name.lowercase(),
                        data = chunk,
                        idController = id,
                        idCooler = id,
                        dateReading = now
                    )
                )
            }
        }
        return logs
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }

    private fun isAllZeros(buffer: String): Boolean = buffer.all { it == '0' }
}
