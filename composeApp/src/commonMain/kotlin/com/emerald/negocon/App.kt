@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.emerald.negocon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.BluetoothService

@Composable
@Preview
fun App() {
    MaterialTheme {
        val controller = remember { BleController(createBlueFalcon()) }
        val selectedPeripheral = controller.selectedPeripheral
        val connectedPeripheral = controller.connectedPeripheral
        val services = controller.services
        val characteristicsByService = controller.characteristicsByService
        val statusMessage = controller.statusMessage
        val compatibility = controller.compatibilityStatus
        val compatibleDevices = controller.devices.filter {
            compatibility[it.uuid] == BleController.CompatibilityStatus.Compatible
        }
        val firmwareProfile = controller.firmwareProfile
        val sessionState = controller.sessionState
        val bufferProgress = controller.bufferProgress
        val lastLogSummary = controller.lastLogSummary
        LazyColumn(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Negocon Demo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(onClick = { controller.toggleScan() }) {
                        Text(if (controller.isScanning) "Detener" else "Escanear")
                    }
                    Button(
                        onClick = { controller.disconnectSelected() },
                        enabled = connectedPeripheral != null
                    ) {
                        Text("Desconectar")
                    }
                }
            }
            if (statusMessage != null) {
                item {
                    Text(
                        text = statusMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (connectedPeripheral != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Conectado",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Firmware: ${firmwareProfile.name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Estado: ${sessionState.name}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(onClick = { controller.collectData() }) {
                                    Text("Obtener datos")
                                }
                                Button(onClick = { controller.deleteData() }) {
                                    Text("Borrar datos")
                                }
                            }
                            Text(
                                text = "Spec: ${controller.deviceSpec?.profile ?: "null"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Read UUID: ${controller.resolvedReadUuid ?: "null"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (bufferProgress.isNotEmpty()) {
                                Text(
                                    text = bufferProgress.entries.joinToString(
                                        prefix = "Progreso: ",
                                        separator = " | "
                                    ) { "${it.key.name.lowercase()}: ${it.value}" },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!lastLogSummary.isNullOrBlank()) {
                                Text(
                                    text = lastLogSummary ?: "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            if (connectedPeripheral != null) {
                item {
                    Divider()
                }
                item {
                    Text(
                        text = "Servicios y caracteristicas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                items(services, key = { it.uuid }) { service ->
                    ServiceCard(
                        service = service,
                        characteristics = characteristicsByService[service.uuid].orEmpty(),
                        peripheral = connectedPeripheral,
                        onRead = { characteristic ->
                            connectedPeripheral?.let { controller.readCharacteristic(it, characteristic) }
                        }
                    )
                }
            }
            item {
                Text(
                    text = "Dispositivos detectados",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            items(compatibleDevices, key = { it.uuid }) { peripheral ->
                DeviceRow(
                    peripheral = peripheral,
                    isSelected = selectedPeripheral?.uuid == peripheral.uuid,
                    isConnected = controller.isConnected(peripheral),
                    compatibilityStatus = compatibility[peripheral.uuid],
                    onSelect = { controller.selectPeripheral(peripheral) }
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    peripheral: BluetoothPeripheral,
    isSelected: Boolean,
    isConnected: Boolean,
    compatibilityStatus: BleController.CompatibilityStatus?,
    onSelect: () -> Unit
) {
    val label = peripheral.name ?: "Dispositivo sin nombre"
    val rssiLabel = peripheral.rssi?.let { "RSSI ${it.toInt()}" } ?: "RSSI N/A"
    val compatibilityLabel = when (compatibilityStatus) {
        BleController.CompatibilityStatus.Compatible -> "Compatible"
        BleController.CompatibilityStatus.Incompatible -> "No compatible"
        null -> ""
    }
    val status = when {
        isConnected -> "Conectado"
        isSelected -> "Seleccionado"
        else -> ""
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(text = peripheral.uuid, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = rssiLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = listOf(status, compatibilityLabel).filter { it.isNotEmpty() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ServiceCard(
    service: BluetoothService,
    characteristics: List<BluetoothCharacteristic>,
    peripheral: BluetoothPeripheral?,
    onRead: (BluetoothCharacteristic) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Servicio ${service.uuid}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (characteristics.isEmpty()) {
                Text(
                    text = "Sin caracteristicas descubiertas",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            characteristics.forEach { characteristic ->
                CharacteristicRow(
                    characteristic = characteristic,
                    canRead = peripheral != null,
                    onRead = { onRead(characteristic) }
                )
            }
        }
    }
}

@Composable
private fun CharacteristicRow(
    characteristic: BluetoothCharacteristic,
    canRead: Boolean,
    onRead: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = characteristic.name ?: "Caracteristica",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = characteristic.uuid.toString(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = onRead,
                enabled = canRead
            ) {
                Text("Leer")
            }
        }
        val valueLabel = formatCharacteristicValue(characteristic.value)
        if (valueLabel.isNotEmpty()) {
            Text(text = "Valor: $valueLabel", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatCharacteristicValue(value: ByteArray?): String {
    if (value == null || value.isEmpty()) return ""
    return value.joinToString(separator = " ") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}
