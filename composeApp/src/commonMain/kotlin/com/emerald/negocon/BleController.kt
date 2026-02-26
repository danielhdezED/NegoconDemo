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
        Pending,
        Checking,
        Compatible,
        Incompatible
    }

    private val probeQueue = ArrayDeque<BluetoothPeripheral>()
    private var probingPeripheral: BluetoothPeripheral? = null
    private var manualPeripheral: BluetoothPeripheral? = null

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
            probeQueue.clear()
            probingPeripheral = null
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
        manualPeripheral = peripheral
        probingPeripheral?.let { blueFalcon.disconnect(it) }
        probingPeripheral = null
        if (isConnected(peripheral)) return
        services.clear()
        characteristicsByService.clear()
        statusMessage = "Conectando a ${peripheral.name ?: "Dispositivo"}"
        stopScan()
        blueFalcon.connect(peripheral, autoConnect = false)
    }

    fun disconnectSelected() {
        manualPeripheral = null
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
        if (manualPeripheral?.uuid == bluetoothPeripheral.uuid) {
            connectedPeripheral = bluetoothPeripheral
            selectedPeripheral = bluetoothPeripheral
            statusMessage = "Conectado a ${bluetoothPeripheral.name ?: "Dispositivo"}"
            isScanning = false
            blueFalcon.stopScanning()
            blueFalcon.discoverServices(bluetoothPeripheral)
        } else {
            probingPeripheral = bluetoothPeripheral
            compatibilityStatus[bluetoothPeripheral.uuid] = CompatibilityStatus.Checking
            blueFalcon.discoverServices(bluetoothPeripheral)
        }
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        if (manualPeripheral?.uuid == bluetoothPeripheral.uuid) {
            manualPeripheral = null
            connectedPeripheral = null
            services.clear()
            characteristicsByService.clear()
            statusMessage = "Dispositivo desconectado"
            return
        }
        if (connectedPeripheral?.uuid == bluetoothPeripheral.uuid) {
            connectedPeripheral = null
            services.clear()
            characteristicsByService.clear()
            statusMessage = "Dispositivo desconectado"
        }
        if (probingPeripheral?.uuid == bluetoothPeripheral.uuid) {
            probingPeripheral = null
            processNextProbe()
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
        if (!compatibilityStatus.containsKey(bluetoothPeripheral.uuid)) {
            compatibilityStatus[bluetoothPeripheral.uuid] = CompatibilityStatus.Pending
            probeQueue.add(bluetoothPeripheral)
        }
        processNextProbe()
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        val serviceList = bluetoothPeripheral.services.values.toList()
        val hasTargetService = serviceList.any {
            uuidToString(it.uuid).equals(BleUuids.targetService, ignoreCase = true)
        }
        if (manualPeripheral?.uuid == bluetoothPeripheral.uuid) {
            if (!hasTargetService) {
                statusMessage = "Dispositivo incompatible: servicio requerido no encontrado"
                blueFalcon.disconnect(bluetoothPeripheral)
                return
            }
            services.clear()
            services.addAll(serviceList)
            serviceList.forEach { service ->
                blueFalcon.discoverCharacteristics(bluetoothPeripheral, service)
            }
            return
        }
        if (!hasTargetService) {
            compatibilityStatus[bluetoothPeripheral.uuid] = CompatibilityStatus.Incompatible
            blueFalcon.disconnect(bluetoothPeripheral)
            return
        }
        serviceList.forEach { service ->
            blueFalcon.discoverCharacteristics(bluetoothPeripheral, service)
        }
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        val hasTargetCharacteristic = bluetoothPeripheral.characteristics.values
            .flatten()
            .any {
                uuidToString(it.uuid).equals(BleUuids.targetCharacteristic, ignoreCase = true)
            }
        if (manualPeripheral?.uuid == bluetoothPeripheral.uuid) {
            bluetoothPeripheral.characteristics.forEach { (serviceUuid, characteristics) ->
                characteristicsByService[serviceUuid] = characteristics
            }
            if (!hasTargetCharacteristic) {
                statusMessage = "Dispositivo incompatible: caracteristica requerida no encontrada"
                blueFalcon.disconnect(bluetoothPeripheral)
            }
            return
        }
        compatibilityStatus[bluetoothPeripheral.uuid] = if (hasTargetCharacteristic) {
            CompatibilityStatus.Compatible
        } else {
            CompatibilityStatus.Incompatible
        }
        blueFalcon.disconnect(bluetoothPeripheral)
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
        devices.clear()
        devices.addAll(sorted)
        sorted.forEach { peripheral ->
            if (!compatibilityStatus.containsKey(peripheral.uuid)) {
                compatibilityStatus[peripheral.uuid] = CompatibilityStatus.Pending
                probeQueue.add(peripheral)
            }
        }
        processNextProbe()
    }

    private fun processNextProbe() {
        if (manualPeripheral != null) return
        if (probingPeripheral != null) return
        val next = probeQueue.removeFirstOrNull() ?: return
        val status = compatibilityStatus[next.uuid]
        if (status != null && status != CompatibilityStatus.Pending) {
            processNextProbe()
            return
        }
        compatibilityStatus[next.uuid] = CompatibilityStatus.Checking
        blueFalcon.connect(next, autoConnect = false)
    }
}
