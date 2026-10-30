package com.chalkie.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalkie.app.core.Ids
import com.chalkie.app.routing.Role
import com.chalkie.app.service.SessionStatus
import com.chalkie.app.transport.TransportType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val LanBlue = Color(0xFF3E8EF7)
private val WdGreen = ListenGreen
private val BleAmber = Amber

private fun color(t: TransportType) = when (t) {
    TransportType.LAN -> LanBlue
    TransportType.WD -> WdGreen
    TransportType.BLE -> BleAmber
}

/**
 * Network map (SPEC §13): me in the middle, direct neighbors on the inner ring, nodes known
 * only through relays outside. Edges colored by transport, BLE edges labelled with RSSI.
 */
@Composable
fun MapTab(vm: ChalkieViewModel, status: SessionStatus?) {
    val measurer = rememberTextMeasurer()
    val edges = remember(status) { vm.topology() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("━ LAN", color = LanBlue, style = MaterialTheme.typography.labelMedium)
            Text("━ Wi-Fi Direct", color = WdGreen, style = MaterialTheme.typography.labelMedium)
            Text("━ Bluetooth", color = BleAmber, style = MaterialTheme.typography.labelMedium)
            Text("H hub · B bridge · S supervisor", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
        }
        if (status == null) {
            Text("Offline", color = Color.Gray)
            return@Column
        }
        Canvas(Modifier.fillMaxSize()) {
            val me = status.myId
            val peers = status.peers.filter { it.heardHello || it.isDirect }
            val direct = peers.filter { it.isDirect }
            val remote = peers.filterNot { it.isDirect }
            val center = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2 - 48.dp.toPx()
            val pos = HashMap<UInt, Offset>()
            pos[me] = center
            fun ring(list: List<*>, radius: Float, ids: List<UInt>, phase: Double) = ids.forEachIndexed { i, id ->
                val a = phase + 2 * PI * i / list.size
                pos[id] = Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
            }
            ring(direct, if (remote.isEmpty()) r * 0.8f else r * 0.5f, direct.map { it.nodeId }, -PI / 2)
            ring(remote, r, remote.map { it.nodeId }, -PI / 2 + PI / maxOf(1, remote.size))

            // Edges: parallel links between the same two nodes are offset so all are visible.
            edges.groupBy { it.a to it.b }.forEach { (_, group) ->
                group.forEachIndexed { i, e ->
                    val p1 = pos[e.a] ?: return@forEachIndexed
                    val p2 = pos[e.b] ?: return@forEachIndexed
                    val d = p2 - p1
                    val len = d.getDistance().coerceAtLeast(1f)
                    val n = Offset(-d.y / len, d.x / len) * ((i - (group.size - 1) / 2f) * 6.dp.toPx())
                    val dashed = e.a != me && e.b != me
                    drawLine(color(e.type), p1 + n, p2 + n, strokeWidth = 3.dp.toPx(),
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null)
                    e.rssi?.let { rssi ->
                        val mid = (p1 + p2) / 2f + n * 2f
                        label(measurer, "$rssi", mid, color(e.type), 10)
                    }
                }
            }

            // Nodes.
            fun node(id: UInt, name: String, battery: Int?, roles: String, isMe: Boolean) {
                val p = pos[id] ?: return
                drawCircle(if (isMe) Amber else Color(0xFF2A323C), 18.dp.toPx(), p)
                drawCircle(Color.White.copy(alpha = 0.6f), 18.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                label(measurer, name.take(2).uppercase(), p, if (isMe) Color(0xFF101418) else Color.White, 12, bold = true)
                val sub = name.take(12) + (battery?.let { " $it%" } ?: "") + if (roles.isNotEmpty()) " $roles" else ""
                // Name outside the graph: above nodes in the top half, below the others.
                val dy = if (p.y < center.y - 1f) -30.dp.toPx() else 30.dp.toPx()
                label(measurer, sub, p + Offset(0f, dy), Color.LightGray, 11)
            }
            peers.forEach { p ->
                val roles = buildString {
                    if (Role.HUB in p.roles) append('H')
                    if (Role.BRIDGE in p.roles) append('B')
                    if (Role.SUPERVISOR in p.roles) append('S')
                }
                node(p.nodeId, p.name.ifBlank { Ids.hex(p.nodeId) }, p.battery.takeIf { p.heardHello }, roles, isMe = false)
            }
            val myRoles = buildString {
                if (status.isHub) append('H')
                if (status.isBridge) append('B')
                if (status.supervisor) append('S')
            }
            node(me, status.myName, null, myRoles, isMe = true)
        }
    }
}

private fun DrawScope.label(
    measurer: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset, color: Color, sizeSp: Int, bold: Boolean = false,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else null))
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}
