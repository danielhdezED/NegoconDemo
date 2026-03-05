package com.emerald.negocon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@Composable
@Preview
fun App() {
    MaterialTheme {
        val controller = remember { BleController(createBlueFalcon()) }
        val connectedPeripheral = controller.connectedPeripheral
        val targetPeripheral = controller.targetPeripheral
        val statusMessage = controller.statusMessage
        val wifiIpAddress = controller.wifiIpAddress
        val wifiIpWarning = controller.wifiIpWarning
        var ssid by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        val passwordFocusRequester = remember { FocusRequester() }
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Negocon BLE",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Objetivo: ${BleUuids.targetDeviceName}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Servicio: ${BleUuids.serviceUuid}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "RX (write): ${BleUuids.rxUuid}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "TX (notify): ${BleUuids.txUuid}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = when {
                            connectedPeripheral != null -> "Estado: Conectado"
                            controller.isConnecting -> "Estado: Conectando"
                            controller.isScanning -> "Estado: Buscando"
                            else -> "Estado: Desconectado"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (targetPeripheral != null) {
                        Text(
                            text = "Dispositivo: ${targetPeripheral.name ?: "negocon-ble"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(onClick = { controller.toggleScan() }) {
                            Text(if (controller.isScanning) "Detener" else "Buscar")
                        }
                        Button(
                            onClick = { controller.connectToTarget() },
                            enabled = connectedPeripheral == null
                        ) {
                            Text("Conectar")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { controller.disconnect() },
                            enabled = connectedPeripheral != null
                        ) {
                            Text("Desconectar")
                        }
                    }
                }
            }

            if (statusMessage != null) {
                Text(
                    text = statusMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (wifiIpAddress != null || wifiIpWarning != null) {
                val ipLabel = when {
                    wifiIpAddress != null -> "IP asignada: $wifiIpAddress"
                    wifiIpWarning == "ip_not_ready" -> "IP pendiente (DHCP en proceso)"
                    else -> "IP pendiente"
                }
                Text(
                    text = ipLabel,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Configurar WiFi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("SSID") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = {
                    passwordFocusRequester.requestFocus()
                }),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password") },
                visualTransformation = if (isPasswordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Text(if (isPasswordVisible) "Ocultar" else "Mostrar")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboardController?.hide()
                    controller.sendWifiConfig(ssid, pass)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocusRequester)
            )
            Button(
                onClick = {
                    keyboardController?.hide()
                    controller.sendWifiConfig(ssid, pass)
                },
                enabled = connectedPeripheral != null
            ) {
                Text("Enviar configuracion")
            }
        }
    }
}
