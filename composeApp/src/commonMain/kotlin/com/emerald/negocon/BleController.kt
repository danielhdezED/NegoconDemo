@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.emerald.negocon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothManagerState
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.BluetoothPeripheralState
import dev.bluefalcon.BluetoothService
import dev.bluefalcon.Uuid
import dev.bluefalcon.BluetoothNotEnabledException
import dev.bluefalcon.BluetoothPermissionException
import dev.bluefalcon.BluetoothResettingException
import dev.bluefalcon.BluetoothUnknownException
import dev.bluefalcon.BluetoothUnsupportedException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BleController(private val blueFalcon: BlueFalcon) : BlueFalconDelegate {
    val devices = mutableStateListOf<BluetoothPeripheral>()
    val services = mutableStateListOf<BluetoothService>()
    val characteristicsByService = mutableStateMapOf<Uuid, List<BluetoothCharacteristic>>()
    val compatibilityStatus = mutableStateMapOf<String, CompatibilityStatus>()

    enum class SessionState {
        Idle,
        DetectingFirmware,
        Ready,
        Collecting,
        Deleting,
        Error
    }

    data class ProbeState(
        val bufferIdHex: String,
        val expectedFrameSize: Int,
        val targetProfile: FirmwareProfile
    )

    enum class CompatibilityStatus {
        Compatible,
        Incompatible
    }

    var isScanning by mutableStateOf(false)
        private set
    var connectedPeripheral by mutableStateOf<BluetoothPeripheral?>(null)
        private set
    var selectedPeripheral by mutableStateOf<BluetoothPeripheral?>(null)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set

    var firmwareProfile by mutableStateOf(FirmwareProfile.UNKNOWN)
        private set
    var sessionState by mutableStateOf(SessionState.Idle)
        private set
    var deviceSpec by mutableStateOf<DeviceSpec?>(null)
        private set
    var lastLogSummary by mutableStateOf<String?>(null)
        private set

    var resolvedReadUuid by mutableStateOf<String?>(null)
        private set

    val bufferProgress = mutableStateMapOf<BufferType, Int>()

    private var probeState: ProbeState? = null
    private val collectedBuffers = mutableStateMapOf<BufferType, SoftelBuffer>()
    private var activeBufferIndex = 0
    private var collectInProgress = false
    private var deleteInProgress = false
    private val emptyBufferRetries = mutableStateMapOf<BufferType, Int>()
    private val completedBuffers = mutableSetOf<BufferType>()
    private val headerCounts = mutableMapOf<BufferType, Int>()

    init {
        blueFalcon.delegates.add(this)
        blueFalcon.peripherals.collectOnMain { updateDevices(it) }
        blueFalcon.managerState
            .onEach { state ->
                if (state == BluetoothManagerState.NotReady) {
                    statusMessage = "Bluetooth no esta listo"
                }
            }
            .launchIn(blueFalcon.scope)
    }

    fun toggleScan() {
        if (isScanning) {
            stopScan()
        } else {
            startScan()
        }
    }

    fun startScan() {
        statusMessage = null
        try {
            blueFalcon.clearPeripherals()
            devices.clear()
            compatibilityStatus.clear()
            resetSession()
            blueFalcon.scan()
            isScanning = true
        } catch (exception: BluetoothPermissionException) {
            statusMessage = "Permisos de Bluetooth requeridos"
        } catch (exception: BluetoothNotEnabledException) {
            statusMessage = "Bluetooth desactivado"
        } catch (exception: BluetoothUnsupportedException) {
            statusMessage = "Bluetooth no soportado"
        } catch (exception: BluetoothResettingException) {
            statusMessage = "Bluetooth reiniciandose"
        } catch (exception: BluetoothUnknownException) {
            statusMessage = "Error al iniciar el escaneo"
        }
    }

    fun stopScan() {
        blueFalcon.stopScanning()
        isScanning = false
    }

    fun selectPeripheral(peripheral: BluetoothPeripheral) {
        selectedPeripheral = peripheral
        if (isConnected(peripheral)) return
        resetSession()
        services.clear()
        characteristicsByService.clear()
        statusMessage = "Conectando a ${peripheral.name ?: "Dispositivo"}"
        stopScan()
        blueFalcon.connect(peripheral, autoConnect = false)
    }

    fun disconnectSelected() {
        resetSession()
        connectedPeripheral?.let { blueFalcon.disconnect(it) }
    }

    fun collectData() {
        println("Collect: click")
        val spec = deviceSpec ?: return
        println("Collect: spec=${spec.profile}")
        val peripheral = connectedPeripheral ?: return
        println("Collect: peripheral=${peripheral.uuid}")
        val notifyUuid = resolvedReadUuid ?: spec.readNotifyUuid
        val notifyCharacteristic = findCharacteristic(peripheral, notifyUuid) ?: return
        println("Collect: notifyUuid=$notifyUuid")
        val headerCharacteristic = findCharacteristic(peripheral, spec.headerUuid)
        headerCharacteristic?.let { blueFalcon.readCharacteristic(peripheral, it) }
        blueFalcon.notifyAndIndicateCharacteristic(peripheral, notifyCharacteristic, true)
        sessionState = SessionState.Collecting
        collectInProgress = true
        deleteInProgress = false
        activeBufferIndex = 0
        collectedBuffers.clear()
        bufferProgress.clear()
        startNextBufferCharge()
    }

    fun deleteData() {
        val spec = deviceSpec ?: return
        val peripheral = connectedPeripheral ?: return
        val writeCharacteristic = findCharacteristic(peripheral, spec.writeUuid) ?: return
        sessionState = SessionState.Deleting
        deleteInProgress = true
        spec.bufferOrder.forEach { bufferType ->
            val bufferId = spec.bufferIds[bufferType] ?: return@forEach
            val command = BleCommandBuilder.buildDelete(bufferId, spec.batchSize)
            if (!sendCommand(peripheral, writeCharacteristic, command, "delete")) {
                return
            }
        }
        deleteInProgress = false
        sessionState = SessionState.Ready
    }

    fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        blueFalcon.readCharacteristic(peripheral, characteristic)
    }

    fun isConnected(peripheral: BluetoothPeripheral): Boolean {
        return blueFalcon.connectionState(peripheral) == BluetoothPeripheralState.Connected
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        connectedPeripheral = bluetoothPeripheral
        selectedPeripheral = bluetoothPeripheral
        statusMessage = "Conectado a ${bluetoothPeripheral.name ?: "Dispositivo"}"
        isScanning = false
        blueFalcon.stopScanning()
        sessionState = SessionState.DetectingFirmware
        blueFalcon.discoverServices(bluetoothPeripheral)
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        if (connectedPeripheral?.uuid == bluetoothPeripheral.uuid) {
            connectedPeripheral = null
            services.clear()
            characteristicsByService.clear()
            statusMessage = "Dispositivo desconectado"
        }
    }

    override fun didDiscoverDevice(
        bluetoothPeripheral: BluetoothPeripheral,
        advertisementData: Map<dev.bluefalcon.AdvertisementDataRetrievalKeys, Any>
    ) {
        val existingIndex = devices.indexOfFirst { it.uuid == bluetoothPeripheral.uuid }
        if (existingIndex >= 0) {
            devices[existingIndex] = bluetoothPeripheral
        } else {
            devices.add(bluetoothPeripheral)
        }
        val manufacturerData = advertisementData[
            dev.bluefalcon.AdvertisementDataRetrievalKeys.ManufacturerData
        ]
        val manufacturerIds = extractManufacturerIds(manufacturerData)
        val status = if (isCompatibleManufacturer(manufacturerIds)) {
            CompatibilityStatus.Compatible
        } else {
            CompatibilityStatus.Incompatible
        }
        if (compatibilityStatus[bluetoothPeripheral.uuid] != status) {
            compatibilityStatus[bluetoothPeripheral.uuid] = status
        }
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        val serviceList = bluetoothPeripheral.services.values.toList()
        val serviceUuids = serviceList.map { uuidToString(it.uuid) }
        firmwareProfile = when {
            serviceUuids.any { it.equals("00040000-0000-1000-8000-00805f9baaaa", true) } -> {
                FirmwareProfile.FW_1_26
            }
            serviceUuids.any { it.equals("00070000-0000-1000-8000-00805f9baaaa", true) } -> {
                FirmwareProfile.UNKNOWN
            }
            else -> FirmwareProfile.UNKNOWN
        }
        services.clear()
        services.addAll(serviceList)
        serviceList.forEach { service ->
            blueFalcon.discoverCharacteristics(bluetoothPeripheral, service)
        }
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        connectedPeripheral = bluetoothPeripheral
        val hasTargetCharacteristic = bluetoothPeripheral.characteristics.values
            .flatten()
            .any {
                uuidToString(it.uuid).equals(BleUuids.targetCharacteristic, ignoreCase = true)
            }
        logDiscoveredCharacteristics(bluetoothPeripheral)
        resolvedReadUuid = resolveReadUuid(bluetoothPeripheral)
        resolvedReadUuid?.let { println("FW Detect: selected read UUID $it") }
        enableCoreNotifications(bluetoothPeripheral)
        bluetoothPeripheral.characteristics.forEach { (serviceUuid, characteristics) ->
            characteristicsByService[serviceUuid] = characteristics
        }
        if (!hasTargetCharacteristic) {
            statusMessage = "Dispositivo incompatible: caracteristica requerida no encontrada"
        }
        if (sessionState == SessionState.DetectingFirmware) {
            when (firmwareProfile) {
                FirmwareProfile.FW_1_26 -> {
                    deviceSpec = SpecRegistry.specFor(FirmwareProfile.FW_1_26)
                    sessionState = SessionState.Ready
                }
                FirmwareProfile.UNKNOWN -> {
                    if (probeState == null) {
                        readHeader(bluetoothPeripheral)
                        println("FW Detect: starting probe 8006")
                        blueFalcon.scope.launch {
                            delay(150)
                            if (firmwareProfile == FirmwareProfile.UNKNOWN && probeState == null) {
                                startProbe(bluetoothPeripheral, "8006", 11, FirmwareProfile.FW_1_36_2_42)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        if (connectedPeripheral?.uuid != bluetoothPeripheral.uuid) return
        val serviceUuid = bluetoothCharacteristic.service?.uuid ?: return
        val updated = bluetoothPeripheral.characteristics[serviceUuid].orEmpty()
        characteristicsByService[serviceUuid] = updated
        val characteristicUuid = uuidToString(bluetoothCharacteristic.uuid)
        val payload = safeCharacteristicValue(bluetoothCharacteristic)
        val payloadSize = payload?.size ?: -1
        println("Notify: uuid=$characteristicUuid size=$payloadSize")
        if (characteristicUuid.equals("00070001-0000-1000-8000-00805f9baaaa", true)) {
            detectFirmwareFromHeader(payload)
            updateHeaderCounts(payload)
        }
        handleProbeIfNeeded(payload)
        handleCollectionIfNeeded(characteristicUuid, payload)
    }

    private fun updateDevices(peripherals: Set<BluetoothPeripheral>) {
        val sorted = peripherals.sortedBy { it.name ?: it.uuid }
        if (devices.size == sorted.size && devices.zip(sorted).all { (left, right) -> left.uuid == right.uuid }) {
            return
        }
        devices.clear()
        devices.addAll(sorted)
    }

    private fun isCompatibleManufacturer(manufacturerIds: Set<Int>): Boolean {
        return manufacturerIds.contains(0x1200) || manufacturerIds.contains(0x3000)
    }

    private fun startProbe(
        peripheral: BluetoothPeripheral,
        bufferIdHex: String,
        expectedFrameSize: Int,
        profile: FirmwareProfile
    ) {
        val spec = SpecRegistry.specFor(FirmwareProfile.FW_1_36_2_42) ?: return
        val writeCharacteristic = findCharacteristic(peripheral, spec.writeUuid)
        val notifyUuid = resolvedReadUuid ?: spec.readNotifyUuid
        val notifyCharacteristic = findCharacteristic(peripheral, notifyUuid)
        if (writeCharacteristic == null || notifyCharacteristic == null) {
            println("FW Probe: characteristics missing for $bufferIdHex")
            return
        }
        println("FW Probe: buffer=$bufferIdHex write=${spec.writeUuid} notify=$notifyUuid")
        blueFalcon.notifyAndIndicateCharacteristic(peripheral, notifyCharacteristic, true)
        probeState = ProbeState(bufferIdHex, expectedFrameSize, profile)
        readHeader(peripheral)
        val command = BleCommandBuilder.buildCharge(bufferIdHex, 1)
        if (!sendCommand(peripheral, writeCharacteristic, command, "probe")) {
            return
        }
        scheduleReadPolling(peripheral, notifyUuid, 3, 250)
        val activeProbe = probeState
        blueFalcon.scope.launch {
            delay(spec.notifyTimeoutMs)
            if (probeState == activeProbe) {
                println("FW Probe timeout for $bufferIdHex")
                if (bufferIdHex == "8006") {
                    startProbe(peripheral, "8015", 15, FirmwareProfile.FW_6_0)
                } else {
                    firmwareProfile = FirmwareProfile.UNKNOWN
                    deviceSpec = null
                    sessionState = SessionState.Ready
                    probeState = null
                }
            }
        }
    }

    private fun handleProbeIfNeeded(value: ByteArray?) {
        val currentProbe = probeState ?: return
        if (firmwareProfile != FirmwareProfile.UNKNOWN) {
            probeState = null
            return
        }
        val payload = value ?: return
        if (payload.isEmpty()) {
            firmwareProfile = currentProbe.targetProfile
            deviceSpec = SpecRegistry.specFor(currentProbe.targetProfile)
            sessionState = SessionState.Ready
            probeState = null
            return
        }
        val signatureMatch = leadingSignatureMatch(payload, signaturesForProbe(currentProbe.bufferIdHex))
        val frameCount = calculateFrameCount(payload.size, currentProbe.expectedFrameSize)
        val valid = signatureMatch != null || frameCount != null
        println("FW Probe response ${currentProbe.bufferIdHex} size=${payload.size} valid=$valid sig=${signatureMatch ?: "none"}")
        if (!valid) {
            return
        }
        val resolvedProfile = signatureMatch?.let { profileForSignature(it) } ?: currentProbe.targetProfile
        firmwareProfile = resolvedProfile
        deviceSpec = SpecRegistry.specFor(resolvedProfile)
        sessionState = SessionState.Ready
        probeState = null
    }

    private fun handleCollectionIfNeeded(characteristicUuid: String, value: ByteArray?) {
        if (!collectInProgress || sessionState != SessionState.Collecting) return
        val spec = deviceSpec ?: return
        val readUuid = resolvedReadUuid ?: spec.readNotifyUuid
        if (!characteristicUuid.equals(readUuid, true)) {
            return
        }
        val payload = value ?: return
        val currentBuffer = spec.bufferOrder.getOrNull(activeBufferIndex) ?: return
        val payloadBufferId = payloadBufferId(payload)
        val payloadBufferType = payloadBufferId?.let { bufferTypeForId(spec, it) }
        val isMarker = isEmptyBufferMarker(payload, null)
        val bufferType = if (!isMarker && payloadBufferType != null) {
            payloadBufferType
        } else {
            currentBuffer
        }
        val frameSize = spec.frameSizes[bufferType] ?: return
        println("Collect: uuid=$characteristicUuid")
        println("Collect: buffer=${bufferType.name.lowercase()} size=${payload.size}")
        if (payload.isEmpty()) {
            val retries = (emptyBufferRetries[bufferType] ?: 0)
            if (retries < 1) {
                emptyBufferRetries[bufferType] = retries + 1
                println("Collect: empty payload, retrying ${bufferType.name.lowercase()} count=${retries + 1}")
                retryCurrentBuffer()
            } else {
                println("Collect: empty payload after retries, finishing")
                finishCollection()
            }
            return
        }
        if (isMarker) {
            val markerHead = payload.take(2).joinToString(" ") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            println("Collect: empty marker for ${bufferType.name.lowercase()} head=$markerHead, moving next")
            completedBuffers.add(bufferType)
            headerCounts[bufferType] = 0
            val isLastBuffer = bufferType == spec.bufferOrder.last()
            if (isLastBuffer) {
                finishCollection()
            } else {
                moveToNextBuffer()
            }
            return
        }
        if (completedBuffers.contains(bufferType)) {
            println(
                "Collect: ignoring payload for completed ${bufferType.name.lowercase()} (id=${payloadBufferId ?: ""})"
            )
            return
        }
        if (bufferType != currentBuffer) {
            val newIndex = spec.bufferOrder.indexOf(bufferType)
            if (newIndex >= 0) {
                activeBufferIndex = newIndex
                println(
                    "Collect: switching buffer to ${bufferType.name.lowercase()} (id=$payloadBufferId)"
                )
            }
        }
        val bufferId = spec.bufferIds[bufferType]
        val frameCount = calculateFrameCount(payload.size, frameSize)
        println("Collect: frames=${frameCount ?: -1}")
        if (frameCount == null) {
            sessionState = SessionState.Error
            statusMessage = "Payload invalido para ${bufferType.name.lowercase()}"
            collectInProgress = false
            return
        }
        val framesCount = frameCount
        val buffer = collectedBuffers.getOrPut(bufferType) {
            SoftelBuffer(bufferType.name.lowercase(), mutableListOf())
        }
        buffer.data.add(payload)
        bufferProgress[bufferType] = (bufferProgress[bufferType] ?: 0) + framesCount
        val peripheral = connectedPeripheral ?: return
        val writeCharacteristic = findCharacteristic(peripheral, spec.writeUuid) ?: return
        val deleteBufferId = bufferId ?: return
        val deleteCommand = BleCommandBuilder.buildDelete(deleteBufferId, framesCount.coerceAtLeast(1))
        if (!sendCommand(peripheral, writeCharacteristic, deleteCommand, "delete")) {
            return
        }
        val remaining = headerCounts[bufferType]?.let { current ->
            (current - framesCount).coerceAtLeast(0)
        }
        if (remaining != null) {
            headerCounts[bufferType] = remaining
        }
        val shouldFinishBuffer = remaining != null && remaining <= 0
        val isLastBuffer = bufferType == spec.bufferOrder.last()
        if (shouldFinishBuffer || framesCount < spec.batchSize) {
            completedBuffers.add(bufferType)
            if (isLastBuffer) {
                finishCollection()
            } else {
                moveToNextBuffer()
            }
        } else {
            blueFalcon.scope.launch {
                delay(150)
                val chargeCommand = BleCommandBuilder.buildCharge(deleteBufferId, spec.batchSize)
                sendCommand(peripheral, writeCharacteristic, chargeCommand, "charge")
            }
        }
    }

    private fun startNextBufferCharge() {
        val spec = deviceSpec ?: return
        val peripheral = connectedPeripheral ?: return
        val bufferType = spec.bufferOrder.getOrNull(activeBufferIndex) ?: return
        if (headerCounts[bufferType] == 0) {
            completedBuffers.add(bufferType)
            moveToNextBuffer()
            return
        }
        val writeCharacteristic = findCharacteristic(peripheral, spec.writeUuid) ?: return
        val bufferId = spec.bufferIds[bufferType] ?: return
        readHeader(peripheral)
        val command = BleCommandBuilder.buildCharge(bufferId, spec.batchSize)
        if (!sendCommand(peripheral, writeCharacteristic, command, "charge")) {
            return
        }
        val notifyUuid = resolvedReadUuid ?: spec.readNotifyUuid
        scheduleReadPolling(peripheral, notifyUuid, 3, 250)
    }

    private fun moveToNextBuffer() {
        activeBufferIndex += 1
        val spec = deviceSpec
        if (spec == null || activeBufferIndex >= spec.bufferOrder.size) {
            finishCollection()
            return
        }
        emptyBufferRetries.clear()
        startNextBufferCharge()
    }

    private fun finishCollection() {
        if (!collectInProgress) return
        collectInProgress = false
        sessionState = SessionState.Ready
        if (collectedBuffers.isEmpty()) {
            statusMessage = "Sin datos disponibles"
            println("Collect: finished without data")
            lastLogSummary = "Buffers: none"
        } else {
            logCollectedData()
        }
    }

    private fun logDiscoveredCharacteristics(peripheral: BluetoothPeripheral) {
        val summary = peripheral.characteristics.entries.joinToString { (serviceUuid, list) ->
            val chars = list.joinToString { uuidToString(it.uuid) }
            "${uuidToString(serviceUuid)}=[${chars}]"
        }
        println("FW Detect: characteristics $summary")
    }

    private fun resolveReadUuid(peripheral: BluetoothPeripheral): String? {
        val candidates = listOf(
            "00070003-0000-1000-8000-00805f9baaaa",
            "00070001-0000-1000-8000-00805f9baaaa",
            "00070002-0000-1000-8000-00805f9baaaa",
            "00040003-0000-1000-8000-00805f9baaaa"
        )
        val all = peripheral.characteristics.values.flatten()
        val byUuid = all.associateBy { uuidToString(it.uuid).lowercase() }
        candidates.firstOrNull { byUuid.containsKey(it) }?.let { return it }
        return all.firstOrNull()?.let { uuidToString(it.uuid) }
    }

    private fun scheduleReadFallback(
        peripheral: BluetoothPeripheral,
        readUuid: String,
        delayMs: Long
    ) {
        val characteristic = findCharacteristic(peripheral, readUuid) ?: return
        blueFalcon.scope.launch {
            delay(delayMs)
            blueFalcon.readCharacteristic(peripheral, characteristic)
        }
    }

    private fun scheduleReadPolling(
        peripheral: BluetoothPeripheral,
        readUuid: String,
        attempts: Int,
        intervalMs: Long
    ) {
        val characteristic = findCharacteristic(peripheral, readUuid) ?: return
        blueFalcon.scope.launch {
            repeat(attempts) { index ->
                delay(intervalMs * (index + 1))
                blueFalcon.readCharacteristic(peripheral, characteristic)
            }
        }
    }

    private fun retryCurrentBuffer() {
        val spec = deviceSpec ?: return
        val peripheral = connectedPeripheral ?: return
        val bufferType = spec.bufferOrder.getOrNull(activeBufferIndex) ?: return
        val writeCharacteristic = findCharacteristic(peripheral, spec.writeUuid) ?: return
        val bufferId = spec.bufferIds[bufferType] ?: return
        readHeader(peripheral)
        blueFalcon.scope.launch {
            delay(200)
            val command = BleCommandBuilder.buildCharge(bufferId, spec.batchSize)
            if (!sendCommand(peripheral, writeCharacteristic, command, "retry")) {
                return@launch
            }
            val notifyUuid = resolvedReadUuid ?: spec.readNotifyUuid
            scheduleReadPolling(peripheral, notifyUuid, 2, 200)
        }
    }

    private fun enableCoreNotifications(peripheral: BluetoothPeripheral) {
        val header = findCharacteristic(peripheral, "00070001-0000-1000-8000-00805f9baaaa")
        val read = resolvedReadUuid?.let { findCharacteristic(peripheral, it) }
        println("Notify: enabling header=${header != null} read=${read != null}")
        header?.let { blueFalcon.notifyAndIndicateCharacteristic(peripheral, it, true) }
        read?.let { blueFalcon.notifyAndIndicateCharacteristic(peripheral, it, true) }
    }

    private fun readHeader(peripheral: BluetoothPeripheral) {
        val headerCharacteristic = findCharacteristic(
            peripheral,
            "00070001-0000-1000-8000-00805f9baaaa"
        ) ?: return
        blueFalcon.readCharacteristic(peripheral, headerCharacteristic)
    }

    private fun detectFirmwareFromHeader(payload: ByteArray?) {
        if (payload == null || payload.isEmpty()) return
        if (firmwareProfile != FirmwareProfile.UNKNOWN) return
        val signature = findSignatureMatch(payload, listOf("8006", "8008", "8015", "8016", "8017", "8018"))
        val profile = signature?.let { profileForSignature(it) } ?: return
        firmwareProfile = profile
        deviceSpec = SpecRegistry.specFor(profile)
        sessionState = SessionState.Ready
        probeState = null
        println("FW Detect: header signature=$signature profile=$profile")
    }

    private fun calculateFrameCount(payloadSize: Int, frameSize: Int): Int? {
        if (frameSize <= 0 || payloadSize <= 0) return null
        if (payloadSize % frameSize == 0) return payloadSize / frameSize
        val headerSize = 8
        if (payloadSize > headerSize && (payloadSize - headerSize) % frameSize == 0) {
            return (payloadSize - headerSize) / frameSize
        }
        return null
    }

    private fun signaturesForProbe(bufferIdHex: String): List<String> {
        return when (bufferIdHex) {
            "8006" -> listOf("8006", "8008")
            "8015" -> listOf("8015", "8016", "8017", "8018")
            else -> listOf(bufferIdHex)
        }
    }

    private fun profileForSignature(signatureHex: String): FirmwareProfile {
        return when (signatureHex) {
            "8006", "8008" -> FirmwareProfile.FW_1_36_2_42
            "8015", "8016", "8017", "8018" -> FirmwareProfile.FW_6_0
            else -> FirmwareProfile.UNKNOWN
        }
    }

    private fun findSignatureMatch(payload: ByteArray, signatures: List<String>): String? {
        signatures.forEach { signatureHex ->
            val signature = signatureHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val reversed = signature.reversedArray()
            if (payloadContains(payload, signature) || payloadContains(payload, reversed)) {
                return signatureHex
            }
        }
        return null
    }

    private fun leadingSignatureMatch(payload: ByteArray, signatures: List<String>): String? {
        if (payload.size < 2) return null
        val head = byteArrayOf(payload[0], payload[1])
        signatures.forEach { signatureHex ->
            val signature = signatureHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val reversed = signature.reversedArray()
            if (head.contentEquals(signature) || head.contentEquals(reversed)) {
                return signatureHex
            }
        }
        return null
    }

    private fun isEmptyBufferMarker(payload: ByteArray, bufferIdHex: String?): Boolean {
        if (payload.size != 8) return false
        if (!payloadIsZeroFrom(payload, 2)) return false
        if (bufferIdHex.isNullOrBlank()) return true
        val signature = bufferIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (signature.size < 2) return true
        val head = byteArrayOf(payload[0], payload[1])
        val reversed = signature.reversedArray()
        return head.contentEquals(signature) || head.contentEquals(reversed)
    }

    private fun payloadBufferId(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val first = payload[0].toInt() and 0xFF
        val second = payload[1].toInt() and 0xFF
        return first.toString(16).padStart(2, '0') + second.toString(16).padStart(2, '0')
    }

    private fun bufferTypeForId(spec: DeviceSpec, bufferIdHex: String): BufferType? {
        val reversed = bufferIdHex.chunked(2).reversed().joinToString("")
        return spec.bufferIds.entries.firstOrNull { (type, id) ->
            id.equals(bufferIdHex, true) || id.equals(reversed, true)
        }?.key
    }

    private fun payloadContains(payload: ByteArray, signature: ByteArray): Boolean {
        if (signature.isEmpty() || payload.size < signature.size) return false
        val limit = payload.size - signature.size
        for (index in 0..limit) {
            var match = true
            for (offset in signature.indices) {
                if (payload[index + offset] != signature[offset]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun payloadIsZeroFrom(payload: ByteArray, startIndex: Int): Boolean {
        for (index in startIndex until payload.size) {
            if (payload[index] != 0.toByte()) return false
        }
        return true
    }

    private fun updateHeaderCounts(payload: ByteArray?) {
        val spec = deviceSpec ?: return
        val data = payload ?: return
        if (data.size < 10) return
        val bufferIds = spec.bufferIds
        bufferIds.forEach { (bufferType, bufferIdHex) ->
            val signature = bufferIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val index = findSequenceIndex(data, signature)
            if (index == null) return@forEach
            val countIndex = index + 4
            if (data.size < countIndex + 4) return@forEach
            val count = readUInt32(data, countIndex)
            headerCounts[bufferType] = count
            println("Header: ${bufferType.name.lowercase()} count=$count")
        }
    }

    private fun findSequenceIndex(payload: ByteArray, signature: ByteArray): Int? {
        if (signature.isEmpty() || payload.size < signature.size) return null
        val limit = payload.size - signature.size
        for (index in 0..limit) {
            var match = true
            for (offset in signature.indices) {
                if (payload[index + offset] != signature[offset]) {
                    match = false
                    break
                }
            }
            if (match) return index
        }
        return null
    }

    private fun readUInt32(payload: ByteArray, startIndex: Int): Int {
        val b0 = payload[startIndex].toInt() and 0xFF
        val b1 = payload[startIndex + 1].toInt() and 0xFF
        val b2 = payload[startIndex + 2].toInt() and 0xFF
        val b3 = payload[startIndex + 3].toInt() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun sendCommand(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        data: ByteArray,
        context: String
    ): Boolean {
        val ok = writeBleCommand(blueFalcon, peripheral, characteristic, data)
        if (!ok) {
            println("Write: blocked ($context) for ${uuidToString(characteristic.uuid)}")
            statusMessage = "iOS: escritura no permitida"
            collectInProgress = false
            deleteInProgress = false
            sessionState = SessionState.Error
        }
        return ok
    }

    private fun safeCharacteristicValue(characteristic: BluetoothCharacteristic): ByteArray? {
        return runCatching { characteristic.value }
            .onFailure { println("Notify: value access failed ${it.message}") }
            .getOrNull()
    }

    private fun logCollectedData() {
        val spec = deviceSpec ?: return
        val peripheralId = connectedPeripheral?.uuid?.replace(":", "") ?: ""
        val logs = SoftelParser.buildReadingLogs(collectedBuffers, peripheralId, spec.frameSizes)
        logs.forEach { log ->
            println("Reading(type=${log.type}, id=${log.idController}, dataSize=${log.data.size})")
        }
        lastLogSummary = buildString {
            append("Buffers: ")
            append(bufferProgress.entries.joinToString { "${it.key.name.lowercase()}:${it.value}" })
        }
    }

    private fun findCharacteristic(
        peripheral: BluetoothPeripheral,
        uuidString: String
    ): BluetoothCharacteristic? {
        val fromCache = characteristicsByService.values.flatten().firstOrNull {
            uuidToString(it.uuid).equals(uuidString, true)
        }
        if (fromCache != null) return fromCache
        val fromPeripheral = peripheral.characteristics.values.flatten().firstOrNull {
            uuidToString(it.uuid).equals(uuidString, true)
        }
        if (fromPeripheral == null) {
            val available = characteristicsByService.values.flatten()
                .joinToString { uuidToString(it.uuid) }
            println("Collect: missing characteristic $uuidString, available=[$available]")
        }
        return fromPeripheral
    }

    private fun resetSession() {
        firmwareProfile = FirmwareProfile.UNKNOWN
        deviceSpec = null
        sessionState = SessionState.Idle
        probeState = null
        collectInProgress = false
        deleteInProgress = false
        bufferProgress.clear()
        collectedBuffers.clear()
        lastLogSummary = null
        resolvedReadUuid = null
        emptyBufferRetries.clear()
        completedBuffers.clear()
        headerCounts.clear()
    }
}
