package com.chalkie.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chalkie.app.core.Ids
import com.chalkie.app.group.GroupInfo
import com.chalkie.app.group.QrCodec
import com.chalkie.app.group.QrImage
import com.chalkie.app.service.SessionStatus
import com.chalkie.app.transport.TransportType
import com.chalkie.app.transport.ble.BleTransport

@Composable
fun ChalkieRoot(vm: ChalkieViewModel) {
    val group by vm.group.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) {
            when (val g = group) {
                GroupState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                GroupState.None -> SetupScreen(vm)
                is GroupState.Joined -> SessionGate(vm, g.info)
            }
        }
    }
}

// ---------------- setup: create or join ----------------

@Composable
private fun SetupScreen(vm: ChalkieViewModel) {
    val context = LocalContext.current
    val savedName by vm.name.collectAsStateWithLifecycle()
    var name by remember(savedName) { mutableStateOf(savedName) }
    var groupName by remember { mutableStateOf("") }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPass by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else Toast.makeText(context, "Camera is needed to scan the group QR", Toast.LENGTH_LONG).show()
    }

    if (scanning) {
        Column(Modifier.fillMaxSize()) {
            Text("Point at the group QR", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                var handled by remember { mutableStateOf(false) }
                QrScanner(Modifier.fillMaxSize()) { text ->
                    if (handled) return@QrScanner
                    if (vm.joinGroup(text)) {
                        handled = true
                        vm.setName(name)
                    }
                }
            }
            TextButton(onClick = { scanning = false }, modifier = Modifier.padding(16.dp)) { Text("Cancel") }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Chalkie", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("Offline push-to-talk for your team. No internet needed.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(name, { name = it.take(24) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Text("Join a team", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                vm.setName(name)
                if (Permissions.hasCamera(context)) scanning = true else cameraLauncher.launch(Manifest.permission.CAMERA)
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp)); Text("Scan group QR")
        }

        Spacer(Modifier.height(8.dp))
        Text("Or create a new team (admin)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(groupName, { groupName = it.take(32) }, label = { Text("Team name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(channels, { channels = it.take(300) }, label = { Text("Extra channels, comma separated (optional)") },
            placeholder = { Text("Splicing, Civil works") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(wifiSsid, { wifiSsid = it.take(32) }, label = { Text("Site Wi-Fi name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (wifiSsid.isNotBlank()) {
            OutlinedTextField(wifiPass, { wifiPass = it.take(63) }, label = { Text("Wi-Fi password (empty if open)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(
            onClick = { vm.setName(name); vm.createGroup(groupName, wifiSsid, wifiPass, channels) },
            enabled = name.isNotBlank() && groupName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Default.Groups, null); Spacer(Modifier.width(8.dp)); Text("Create team")
        }
    }
}

// ---------------- permissions, then session ----------------

@Composable
private fun SessionGate(vm: ChalkieViewModel, group: GroupInfo) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(Permissions.missing(context)) }
    var batteryOk by remember { mutableStateOf(Permissions.ignoringBatteryOptimizations(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missing = Permissions.missing(context)
        batteryOk = Permissions.ignoringBatteryOptimizations(context)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = Permissions.missing(context)
    }

    if (missing.isNotEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Permissions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Chalkie needs the microphone to talk, Bluetooth to find nearby teammates without internet, and notifications to keep running in the background.")
            Button(onClick = { launcher.launch(Permissions.session) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Allow")
            }
        }
        return
    }

    val running by vm.running.collectAsStateWithLifecycle()
    // Re-sent on every resume so a service restarted in the background regains the microphone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (vm.running.value) vm.startService() }
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!running && !autoStarted) { autoStarted = true; vm.startService() }
    }
    MainScreen(vm, group, running, batteryOk)
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TALK("Talk", Icons.Default.Mic),
    TEAM("Team", Icons.Default.Groups),
    MESSAGES("Messages", Icons.Default.Chat),
    MAP("Map", Icons.Default.Hub),
    LOG("Log", Icons.Default.List),
}

@Composable
private fun MainScreen(vm: ChalkieViewModel, group: GroupInfo, running: Boolean, batteryOk: Boolean) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showQr by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        status?.let { "${it.myName} · ${Ids.hex(it.myId)}" + if (it.supervisor) " · supervisor" else "" } ?: "",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                IconButton(onClick = { showQr = true }) { Icon(Icons.Default.QrCode, "Invite") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
            }
            StatusBar(status, running)
            Spacer(Modifier.height(4.dp))
            Counters(status)

            if (running && status?.bleState == BleTransport.STATE_BT_OFF) {
                Banner("Bluetooth is off. You can't hear or reach your team.", "Turn on", TalkRed) {
                    runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                }
            }
            if (!batteryOk) {
                Banner("Allow Chalkie to run in the background so you keep hearing your team with the screen off.", "Allow",
                    MaterialTheme.colorScheme.surfaceVariant) { Permissions.requestBatteryExemption(context) }
            }
            if (!running) {
                Banner("You are offline.", "Go online", MaterialTheme.colorScheme.surfaceVariant) { vm.startService() }
            }
            val learning by vm.learningKey.collectAsStateWithLifecycle()
            if (learning) {
                Banner("Press the key you want to use as PTT…", "Cancel", Amber.copy(alpha = 0.3f)) { vm.learningKey.value = false }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            when (Tab.entries[tab]) {
                Tab.TALK -> TalkTab(vm, group, status, running)
                Tab.TEAM -> TeamTab(vm, status)
                Tab.MESSAGES -> MessagesTab(vm, group)
                Tab.MAP -> MapTab(vm, status)
                Tab.LOG -> LogTab(vm, group)
            }
        }

        NavigationBar {
            Tab.entries.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
            }
        }
    }

    if (showQr) QrDialog(group) { showQr = false }
    if (showSettings) SettingsDialog(vm, running) { showSettings = false }
}

@Composable
private fun Banner(text: String, action: String, color: Color, onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Surface(color = color.copy(alpha = if (color == TalkRed) 0.2f else 1f), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun StatusBar(status: SessionStatus?, running: Boolean) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val bleActive = running && status?.bleState == "active"
        Chip(
            icon = { Icon(if (bleActive) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled, null, Modifier.size(16.dp)) },
            text = if (!running) "Offline" else "BLE ${status?.bleLinks ?: 0}" + if (bleActive) "" else " · ${status?.bleState}",
            color = if (bleActive) ListenGreen else Color.Gray,
        )
        val wd = status?.wdState ?: "off"
        Chip(
            text = when {
                !running -> "WD off"
                wd == "hub" -> "WD hub · ${status?.wdLinks ?: 0}"
                wd == "leaf" -> if ((status?.wdLinks ?: 0) > 0) "WD leaf" else "WD joining…"
                else -> "WD $wd"
            },
            color = if (running && (status?.wdLinks ?: 0) > 0) ListenGreen else if (running && wd == "hub") Amber else Color.DarkGray,
        )
        val lan = if (running) status?.lanState ?: "off" else "off"
        Chip(
            text = when (lan) {
                "active_multicast" -> "LAN · ${status?.lanLinks ?: 0}"
                "active_unicast" -> "LAN unicast · ${status?.lanLinks ?: 0}"
                "probing" -> "LAN checking…"
                "standby" -> "LAN standby"
                "isolated" -> "LAN blocked"
                else -> "LAN off"
            },
            color = when (lan) {
                "active_multicast", "active_unicast" -> ListenGreen
                "probing" -> Amber
                "isolated" -> TalkRed
                else -> Color.DarkGray
            },
        )
        if (running && status?.isBridge == true) Chip(text = "Bridge", color = Amber)
        if (running) Chip(text = "${status?.peers?.count { it.heardHello } ?: 0} peers", color = Color.Gray)
    }
}

/** Debug: packets sent/received per transport, as counted by the router. */
@Composable
private fun Counters(status: SessionStatus?) {
    val c = status?.counters ?: return
    Text(
        listOf(TransportType.BLE, TransportType.WD, TransportType.LAN).joinToString("   ") { t ->
            val (tx, rx) = c[t] ?: (0L to 0L)
            "${t.name} ↑$tx ↓$rx"
        } + if (status.outbox > 0) "   ✉ ${status.outbox} waiting" else "",
        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
    )
}

@Composable
fun Chip(text: String, color: Color, icon: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { it(); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun QrDialog(group: GroupInfo, onDismiss: () -> Unit) {
    var supervisorQr by remember { mutableStateOf(false) }
    val shown = group.copy(supervisor = group.supervisor && supervisorQr)
    val bitmap = remember(shown) { QrImage.render(QrCodec.encode(shown)).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite to ${group.name}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (group.supervisor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!supervisorQr, { supervisorQr = false }, label = { Text("Team member") })
                        FilterChip(supervisorQr, { supervisorQr = true }, label = { Text("Supervisor") })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Image(bitmap, "Team QR code", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (shown.supervisor) "Supervisor code: whoever scans it can talk over the team. " else "") +
                        "Scan this with Chalkie on the other phone. Anyone with this code can join, so only show it to your team.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
