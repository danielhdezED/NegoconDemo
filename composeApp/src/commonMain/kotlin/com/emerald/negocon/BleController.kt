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

class BleController(private val blueFalcon: BlueFalcon) : BlueFalconDelegate {
    val devices = mutableStateListOf<BluetoothPeripheral>()
    val services = mutableStateListOf<BluetoothService>()
    val characteristicsByService = mutableStateMapOf<Uuid, List<BluetoothCharacteristic>>()
    val compatibilityStatus = mutableStateMapOf<String, CompatibilityStatus>()

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
        services.clear()
        characteristicsByService.clear()
        statusMessage = "Conectando a ${peripheral.name ?: "Dispositivo"}"
        stopScan()
        blueFalcon.connect(peripheral, autoConnect = false)
    }

    fun disconnectSelected() {
        connectedPeripheral?.let { blueFalcon.disconnect(it) }
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
        val hasTargetService = serviceList.any {
            uuidToString(it.uuid).equals(BleUuids.targetService, ignoreCase = true)
        }
        serviceList.forEach { service ->
            blueFalcon.discoverCharacteristics(bluetoothPeripheral, service)
        }
        if (!hasTargetService) {
            statusMessage = "Dispositivo incompatible: servicio requerido no encontrado"
        }
        services.clear()
        services.addAll(serviceList)
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        val hasTargetCharacteristic = bluetoothPeripheral.characteristics.values
            .flatten()
            .any {
                uuidToString(it.uuid).equals(BleUuids.targetCharacteristic, ignoreCase = true)
            }
        bluetoothPeripheral.characteristics.forEach { (serviceUuid, characteristics) ->
            characteristicsByService[serviceUuid] = characteristics
        }
        if (!hasTargetCharacteristic) {
            statusMessage = "Dispositivo incompatible: caracteristica requerida no encontrada"
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

    private fun extractManufacturerIds(
        advertisementData: Map<dev.bluefalcon.AdvertisementDataRetrievalKeys, Any>
    ): Set<Int> {
        val data = advertisementData[dev.bluefalcon.AdvertisementDataRetrievalKeys.ManufacturerData]
        return when (data) {
            is ByteArray -> parseManufacturerIdsFromBytes(data)
            is Map<*, *> -> data.keys.filterIsInstance<Int>().toSet()
            else -> emptySet()
        }
    }

    private fun parseManufacturerIdsFromBytes(data: ByteArray): Set<Int> {
        if (data.size < 2) return emptySet()
        val manufacturerId = (data[0].toUByte().toInt() or (data[1].toUByte().toInt() shl 8))
        return setOf(manufacturerId)
    }
}
