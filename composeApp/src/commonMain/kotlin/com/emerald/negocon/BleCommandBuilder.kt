package com.emerald.negocon

object BleCommandBuilder {
    fun buildCharge(bufferIdHex: String, count: Int): ByteArray {
        return buildCommand("0001", bufferIdHex, count)
    }

    fun buildDelete(bufferIdHex: String, count: Int): ByteArray {
        return buildCommand("0002", bufferIdHex, count)
    }

    private fun buildCommand(opHex: String, bufferIdHex: String, count: Int): ByteArray {
        val countHex = count.coerceAtLeast(1).toString(16).padStart(4, '0')
        val payload = opHex + bufferIdHex + "00000000" + countHex
        return payload.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
