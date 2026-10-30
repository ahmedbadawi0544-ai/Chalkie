package com.chalkie.app.ui

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Ids
import com.chalkie.app.data.VoiceMessageEntity
import com.chalkie.app.group.GroupInfo
import com.chalkie.app.message.VoiceMessage
import com.chalkie.app.routing.LinkCost
import com.chalkie.app.routing.NeighborSnapshot
import com.chalkie.app.routing.Role
import com.chalkie.app.service.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
private val Ink = Color(0xFF101418)

/** Press-and-hold control: [onPress] returns false if refused (busy), [onRelease] runs only after a granted press. */
@Composable
fun HoldButton(
    modifier: Modifier,
    color: Color,
    enabled: Boolean,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier.background(if (enabled) color else Color.DarkGray).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = {
                val granted = press()
                haptics.performHapticFeedback(if (granted) HapticFeedbackType.LongPress else HapticFeedbackType.Reject)
                tryAwaitRelease()
                if (granted) release()
            })
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------- Talk ----------------

@Composable
fun TalkTab(vm: ChalkieViewModel, group: GroupInfo, status: SessionStatus?, running: Boolean) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val enabled = running && status != null
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        ChannelPicker(group, status?.channel ?: settings.channel, enabled = enabled && status?.talking != true) { vm.setChannel(it) }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val talking = status?.talking == true
            val other = status?.talkerName?.takeIf { !talking && it != "You" }
            val color = when {
                talking -> TalkRed
                other != null -> ListenGreen
                else -> Amber
            }
            HoldButton(
                Modifier.size(220.dp).clip(CircleShape), color, enabled,
                onPress = { vm.pttDown() }, onRelease = { vm.pttUp() },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (other != null) Icons.Default.VolumeUp else Icons.Default.Mic, null, Modifier.size(64.dp), tint = Ink)
                    Text(
                        when {
                            !enabled -> "OFFLINE"
                            talking -> if (status?.privateTarget != null) "PRIVATE" else "TALKING"
                            other != null -> other.uppercase()
                            else -> "HOLD TO TALK"
                        },
                        color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(status?.talkLine ?: " ", style = MaterialTheme.typography.bodyMedium)
        Text(status?.lastEvent ?: " ", style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.quickTexts.forEach { t ->
                AssistChip(onClick = {
                    val ok = vm.sendText(t)
                    Toast.makeText(context, if (ok) "Sent to ${group.channelName(status?.channel ?: 0)}" else "Not sent", Toast.LENGTH_SHORT).show()
                }, label = { Text(t) }, enabled = enabled)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChannelPicker(group: GroupInfo, channel: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("Channel: ${group.channelName(channel)}")
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            (0 until ChalkieConfig.CHANNEL_COUNT).forEach { i ->
                DropdownMenuItem(
                    text = { Text((if (i == channel) "● " else "") + group.channelName(i) + if (i == 0) "  (everyone hears)" else "") },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

// ---------------- Team ----------------

@Composable
fun TeamTab(vm: ChalkieViewModel, status: SessionStatus?) {
    var selected by remember { mutableStateOf<UInt?>(null) }
    val peers = status?.peers.orEmpty()
    LazyColumn(Modifier.fillMaxSize()) {
        if (peers.isEmpty()) item {
            Text("Looking for teammates nearby…", Modifier.padding(vertical = 16.dp), color = Color.Gray)
        }
        item { Text("Tap a teammate to call privately, text, or leave a voice message.", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        items(peers, key = { it.nodeId.toLong() }) { p -> PeerRow(p) { selected = p.nodeId } }
    }
    val peer = peers.firstOrNull { it.nodeId == selected }
    if (peer != null) PeerDialog(vm, peer, status) { selected = null }
}

@Composable
private fun PeerRow(p: NeighborSnapshot, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (p.isDirect) ListenGreen else Amber))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { Ids.hex(p.nodeId) } + roleBadges(p.roles), fontWeight = FontWeight.Medium)
            Text(linksText(p), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (p.lastHelloMs > 0 && p.name.isNotBlank()) {
            Text("${p.battery}%" + if (p.charging) " ⚡" else "", style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun roleBadges(roles: Set<Role>): String = buildList {
    if (Role.HUB in roles) add("hub")
    if (Role.BRIDGE in roles) add("bridge")
    if (Role.SUPERVISOR in roles) add("supervisor")
}.joinToString("") { " · $it" }

/** All live links, cheapest first; ★ marks the one routing prefers (SPEC §6). */
private fun linksText(p: NeighborSnapshot): String = if (p.isDirect) {
    val best = LinkCost.best(p.links.values)?.type
    p.links.values.sortedBy { LinkCost.of(it) ?: Double.MAX_VALUE }.joinToString(" + ") { l ->
        l.type.name + (if (l.type == best) " ★" else "") + (l.rssi?.let { " $it dBm" } ?: "")
    }
} else {
    "via ${p.hopCount} relay" + if (p.hopCount == 1) "" else "s"
}

@Composable
private fun PeerDialog(vm: ChalkieViewModel, p: NeighborSnapshot, status: SessionStatus?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val name = p.name.ifBlank { Ids.hex(p.nodeId) }
    val running = status != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name + roleBadges(p.roles)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(linksText(p), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                val calling = status?.privateTarget == name
                HoldButton(
                    Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)),
                    if (calling) TalkRed else Amber, running,
                    onPress = { vm.pttDown(target = p.nodeId) }, onRelease = { vm.pttUp() },
                ) { Text(if (calling) "TALKING TO $name" else "Hold to call privately", color = Ink, fontWeight = FontWeight.Bold) }

                val recording = status?.recording == true
                HoldButton(
                    Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)),
                    if (recording) TalkRed else MaterialTheme.colorScheme.surfaceVariant, running,
                    onPress = { vm.startRecording() },
                    onRelease = {
                        val ok = vm.finishRecording(p.nodeId)
                        Toast.makeText(context, if (ok) "Voice message for $name queued" else "Nothing recorded", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text(
                        if (recording) "Recording… release to send" else "Hold to record a voice message",
                        color = if (recording) Ink else Color.White, fontWeight = FontWeight.Bold,
                    )
                }
                Text("Voice messages wait on your phone until $name is reachable (max ${ChalkieConfig.VMSG_MAX_MS / 1000} s).",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                HorizontalDivider()
                Text("Quick text to $name", style = MaterialTheme.typography.titleSmall)
                settings.quickTexts.forEach { t ->
                    OutlinedButton(onClick = {
                        val ok = vm.sendText(t, p.nodeId)
                        Toast.makeText(context, if (ok) "Sent to $name" else "Not sent", Toast.LENGTH_SHORT).show()
                    }, enabled = running, modifier = Modifier.fillMaxWidth()) { Text(t) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------- Messages ----------------

@Composable
fun MessagesTab(vm: ChalkieViewModel, group: GroupInfo) {
    val messages by vm.voiceMessages.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Voice messages", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall) }
        if (messages.isEmpty()) item {
            Text("None yet. Open a teammate in Team to record one.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        items(messages, key = { it.uid }) { m -> VoiceMessageRow(vm, m) }
        item { Text("Texts", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall) }
        if (chat.isEmpty()) item {
            Text("No texts in this session.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        items(chat.reversed()) { c ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val where = c.privatePeer?.let { if (c.mine) "to $it (private)" else "private" } ?: group.channelName(c.channel)
                Text("${timeFmt.format(Date(c.timeMs))} · ${c.from} · $where", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(c.text, color = if (c.mine) Amber else Color.White)
            }
        }
    }
}

@Composable
private fun VoiceMessageRow(vm: ChalkieViewModel, m: VoiceMessageEntity) {
    val secs = remember(m.uid) {
        VoiceMessage.decodeBody(m.uid, 0u, 0u, m.body)?.durationMs?.let { (it + 500) / 1000 } ?: 0
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!m.outgoing && !m.heard) Box(Modifier.size(8.dp).clip(CircleShape).background(Amber)) else Spacer(Modifier.size(8.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text((if (m.outgoing) "To " else "From ") + m.peerName + " · ${secs}s", fontWeight = FontWeight.Medium)
            Text(
                dateTimeFmt.format(Date(m.createdAt)) + " · " + when {
                    !m.outgoing -> "received"
                    m.delivered -> "delivered ✓"
                    else -> "waiting for ${m.peerName}…"
                },
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        IconButton(onClick = { vm.play(m) }) { Icon(Icons.Default.PlayArrow, "Play") }
        IconButton(onClick = { vm.delete(m) }) { Icon(Icons.Default.Delete, "Delete") }
    }
}

// ---------------- Log ----------------

@Composable
fun LogTab(vm: ChalkieViewModel, group: GroupInfo) {
    val context = LocalContext.current
    val entries by vm.log.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri) { ok ->
            Toast.makeText(context, if (ok) "Log exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                export.launch("chalkie-log-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv")
            }, modifier = Modifier.weight(1f)) { Text("Export CSV") }
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (entries.isEmpty()) item { Text("Nothing logged yet.", color = Color.Gray) }
            items(entries, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        "${dateTimeFmt.format(Date(e.timeMs))} · ${e.kind.lowercase().replace('_', ' ')}" +
                            (e.channel?.let { " · ${group.channelName(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                    )
                    Text(
                        e.who + (e.durationMs?.let { " · ${(it + 500) / 1000}s" } ?: "") + (e.text?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the log?") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearLog() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

// ---------------- Settings ----------------

@Composable
fun SettingsDialog(vm: ChalkieViewModel, running: Boolean, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var quick by remember(settings.quickTexts) { mutableStateOf(settings.quickTexts.joinToString("\n")) }
    var confirmLeave by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PTT key (while Chalkie is open)", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (settings.pttKeyCode == 0) "Off" else KeyEvent.keyCodeToString(settings.pttKeyCode).removePrefix("KEYCODE_"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setPttKey(KeyEvent.KEYCODE_VOLUME_DOWN) }) { Text("Volume −") }
                    OutlinedButton(onClick = { vm.learningKey.value = true; onDismiss() }) { Text("Learn key") }
                    OutlinedButton(onClick = { vm.setPttKey(0) }) { Text("Off") }
                }
                Text("“Learn key” works for side keys such as the Samsung XCover / Tab Active key when it is set to open no app.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                SettingSwitch("Rugged-phone PTT button", "Listen for the PTT key broadcasts of rugged phones, also with the screen off.",
                    settings.hardwarePtt, vm::setHardwarePtt)
                SettingSwitch("Opus voice codec", "Uses about a quarter of the radio time of G.711. Turn off if voice sounds broken.",
                    settings.useOpus, vm::setUseOpus)
                Text("A headset or Bluetooth button also works: short press to start/stop, or hold to talk.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                HorizontalDivider()
                Text("Quick texts (one per line)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(quick, { quick = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                OutlinedButton(onClick = { vm.setQuickTexts(quick.lines()) }) { Text("Save quick texts") }

                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (running) vm.stopService() else vm.startService() }) {
                        Text(if (running) "Go offline" else "Go online")
                    }
                    OutlinedButton(onClick = { confirmLeave = true }) { Text("Leave team") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Leave this team?") },
        text = { Text("You will need to scan the team QR again to rejoin.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onDismiss(); vm.leaveGroup() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked, onChange)
    }
}
