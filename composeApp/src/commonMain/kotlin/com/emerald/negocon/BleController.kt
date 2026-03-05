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
import kotlinx.coroutines.Job

class BleController(private val blueFalcon: BlueFalcon) : BlueFalconDelegate {
    val services = mutableStateListOf<BluetoothService>()
    val characteristicsByService = mutableStateMapOf<Uuid, List<BluetoothCharacteristic>>()

    var isScanning by mutableStateOf(false)
        private set
    var isConnecting by mutableStateOf(false)
        private set
    var connectedPeripheral by mutableStateOf<BluetoothPeripheral?>(null)
        private set
    var targetPeripheral by mutableStateOf<BluetoothPeripheral?>(null)
        private set
    var rxCharacteristic by mutableStateOf<BluetoothCharacteristic?>(null)
        private set
    var txCharacteristic by mutableStateOf<BluetoothCharacteristic?>(null)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var wifiIpAddress by mutableStateOf<String?>(null)
        private set
    var wifiIpWarning by mutableStateOf<String?>(null)
        private set

    private var discoveryJob: Job? = null
    private var discoveryInProgress = false

    init {
        blueFalcon.delegates.add(this)
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
            targetPeripheral = null
            services.clear()
            characteristicsByService.clear()
            rxCharacteristic = null
            txCharacteristic = null
            discoveryJob?.cancel()
            discoveryInProgress = false
            val filter = createServiceFilter(BleUuids.serviceUuid)
            blueFalcon.scan(listOf(filter))
            isScanning = true
            statusMessage = "Buscando ${BleUuids.targetDeviceName}"
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

    fun connectToTarget() {
        val peripheral = targetPeripheral
        if (peripheral == null) {
            startScan()
            return
        }
        if (isConnected(peripheral) || isConnecting) return
        services.clear()
        characteristicsByService.clear()
        rxCharacteristic = null
        txCharacteristic = null
        discoveryJob?.cancel()
        discoveryInProgress = false
        statusMessage = "Conectando a ${peripheral.name ?: "Dispositivo"}"
        stopScan()
        isConnecting = true
        connectWithDelay(peripheral)
    }

    fun disconnect() {
        connectedPeripheral?.let { blueFalcon.disconnect(it) }
    }

    fun sendWifiConfig(ssid: String, pass: String) {
        if (ssid.isBlank() || pass.isBlank()) {
            statusMessage = "Completa SSID y password"
            return
        }
        val peripheral = connectedPeripheral
        if (peripheral == null) {
            statusMessage = "No hay dispositivo conectado"
            return
        }
        val characteristic = rxCharacteristic
        if (characteristic == null) {
            statusMessage = "Sin caracteristica RX"
            return
        }
        val payload = buildWifiConfigJson(ssid, pass)
        val ok = writeBleCommand(
            blueFalcon,
            peripheral,
            characteristic,
            payload.encodeToByteArray()
        )
        statusMessage = if (ok) "Configuracion enviada" else "No se pudo enviar"
    }

    fun isConnected(peripheral: BluetoothPeripheral): Boolean {
        return blueFalcon.connectionState(peripheral) == BluetoothPeripheralState.Connected
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        connectedPeripheral = bluetoothPeripheral
        targetPeripheral = bluetoothPeripheral
        statusMessage = "Conectado a ${bluetoothPeripheral.name ?: "Dispositivo"}"
        isConnecting = false
        isScanning = false
        blueFalcon.stopScanning()
        blueFalcon.discoverServices(bluetoothPeripheral)
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        if (connectedPeripheral?.uuid == bluetoothPeripheral.uuid) {
            connectedPeripheral = null
            services.clear()
            characteristicsByService.clear()
            rxCharacteristic = null
            txCharacteristic = null
            discoveryJob?.cancel()
            discoveryInProgress = false
            isConnecting = false
            statusMessage = "Dispositivo desconectado"
        }
    }

    override fun didDiscoverDevice(
        bluetoothPeripheral: BluetoothPeripheral,
        advertisementData: Map<dev.bluefalcon.AdvertisementDataRetrievalKeys, Any>
    ) {
        val matchesService = advertisesTargetService(advertisementData)
        val name = bluetoothPeripheral.name
        val matchesName = name?.equals(BleUuids.targetDeviceName, ignoreCase = true) == true
        if (!matchesService && !matchesName) return
        targetPeripheral = bluetoothPeripheral
        if (isScanning && !isConnecting && connectedPeripheral?.uuid != bluetoothPeripheral.uuid) {
            statusMessage = "Encontrado ${BleUuids.targetDeviceName}, conectando"
            isConnecting = true
            stopScan()
            connectWithDelay(bluetoothPeripheral)
        }
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        val serviceList = bluetoothPeripheral.services.values.toList()
        services.clear()
        services.addAll(serviceList)
        discoveryInProgress = true
        startDiscoveryTimeout(bluetoothPeripheral)
        val hasTargetService = serviceList.any {
            uuidToString(it.uuid).equals(BleUuids.serviceUuid, ignoreCase = true)
        }
        if (!hasTargetService) {
            statusMessage = "Servicio BLE requerido no encontrado"
        }
        serviceList.forEach { service ->
            blueFalcon.discoverCharacteristics(bluetoothPeripheral, service)
        }
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothPeripheral.characteristics.forEach { (serviceUuid, characteristics) ->
            characteristicsByService[serviceUuid] = characteristics
        }
        rxCharacteristic = findCharacteristic(bluetoothPeripheral, BleUuids.rxUuid)
        txCharacteristic = findCharacteristic(bluetoothPeripheral, BleUuids.txUuid)
        if (rxCharacteristic != null) {
            clearRxError()
        }
        txCharacteristic?.let { characteristic ->
            blueFalcon.notifyCharacteristic(bluetoothPeripheral, characteristic, true)
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
        if (!characteristicUuid.equals(BleUuids.txUuid, ignoreCase = true)) return
        val payload = bluetoothCharacteristic.value
        val text = payload?.decodeToString()?.trim().orEmpty()
        if (text.isEmpty()) return
        handleAckPayload(text)
    }

    private fun findCharacteristic(
        peripheral: BluetoothPeripheral,
        uuidString: String
    ): BluetoothCharacteristic? {
        val all = peripheral.characteristics.values.flatten()
        return all.firstOrNull { uuidToString(it.uuid).equals(uuidString, true) }
    }

    private fun advertisesTargetService(
        advertisementData: Map<dev.bluefalcon.AdvertisementDataRetrievalKeys, Any>
    ): Boolean {
        return advertisementData.values.any { value ->
            when (value) {
                is List<*> -> value.any { matchesServiceUuid(it) }
                is Array<*> -> value.any { matchesServiceUuid(it) }
                else -> matchesServiceUuid(value)
            }
        }
    }

    private fun matchesServiceUuid(value: Any?): Boolean {
        return when (value) {
            is Uuid -> uuidToString(value).equals(BleUuids.serviceUuid, true)
            is String -> value.equals(BleUuids.serviceUuid, true)
            else -> value?.toString()?.equals(BleUuids.serviceUuid, true) == true
        }
    }

    private fun handleAckPayload(text: String) {
        val ackMatch = Regex("\"ack\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
        val cmd = Regex("\"cmd\"\\s*:\\s*\"([^\"]+)\"")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        val error = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        val ipValue = Regex("\"ip\"\\s*:\\s*(null|\"([^\"]*)\")", RegexOption.IGNORE_CASE)
            .find(text)
        val ip = ipValue?.groupValues?.getOrNull(2)
        val warn = Regex("\"warn\"\\s*:\\s*\"([^\"]+)\"")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

        wifiIpAddress = ip?.takeIf { it.isNotBlank() }
        wifiIpWarning = warn

        if (error != null) {
            statusMessage = if (cmd != null) {
                "Error al configurar ${cmd.lowercase()}: $error"
            } else {
                "Error: $error"
            }
            return
        }

        statusMessage = when (ackMatch) {
            "true" -> if (cmd?.lowercase() == "wifi") {
                "WiFi configurado correctamente"
            } else {
                "Comando confirmado"
            }
            "false" -> if (cmd?.lowercase() == "wifi") {
                "Fallo al configurar WiFi"
            } else {
                "Comando rechazado"
            }
            else -> text
        }
    }

    private fun connectWithDelay(peripheral: BluetoothPeripheral) {
        blueFalcon.scope.launch {
            delay(500)
            blueFalcon.connect(peripheral, autoConnect = false)
        }
    }

    private fun startDiscoveryTimeout(peripheral: BluetoothPeripheral) {
        discoveryJob?.cancel()
        discoveryJob = blueFalcon.scope.launch {
            delay(4000)
            discoveryInProgress = false
            if (connectedPeripheral?.uuid != peripheral.uuid) return@launch
            if (rxCharacteristic == null) {
                statusMessage = "No se encontro caracteristica RX"
            }
        }
    }

    private fun clearRxError() {
        if (statusMessage == "No se encontro caracteristica RX") {
            statusMessage = null
        }
    }

    private fun buildWifiConfigJson(ssid: String, pass: String): String {
        val safeSsid = escapeJson(ssid)
        val safePass = escapeJson(pass)
        return "{\"cmd\":\"wifi\",\"ssid\":\"$safeSsid\",\"pass\":\"$safePass\"}"
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
