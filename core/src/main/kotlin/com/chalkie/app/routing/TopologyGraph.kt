package com.chalkie.app.routing

import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.TransportType
import java.util.PriorityQueue

/** One link in the team graph. Undirected: [a] < [b]. */
data class TopoEdge(val a: UInt, val b: UInt, val type: TransportType, val rssi: Int?, val cost: Double)

/**
 * Team topology (SPEC §6): my own links plus the links every node reports in its HELLO.
 * Used for unicast next hops (Dijkstra over [LinkCost]) and drawn by the network map.
 */
object TopologyGraph {

    fun edges(myId: UInt, nodes: List<NeighborSnapshot>): List<TopoEdge> {
        val out = LinkedHashMap<Triple<UInt, UInt, TransportType>, TopoEdge>()
        fun add(x: UInt, y: UInt, link: LinkInfo, own: Boolean) {
            if (x == y) return
            val cost = LinkCost.of(link) ?: return
            val (a, b) = if (x < y) x to y else y to x
            val key = Triple(a, b, link.type)
            // My own measurement wins over what the other side reports.
            if (own || key !in out) out[key] = TopoEdge(a, b, link.type, link.rssi, cost)
        }
        for (n in nodes) {
            n.links.values.forEach { add(myId, n.nodeId, it, own = true) }
            // Links to me: only my own (live) view counts, a report may be stale.
            n.reportedNeighbors.filter { it.id != myId }.forEach { r ->
                add(n.nodeId, r.id, LinkInfo(r.id, r.type, r.rssi, 0f, null, 0), own = false)
            }
        }
        return out.values.toList()
    }

    /** First hop on the cheapest path from [myId] to [dst], or null if [dst] is unreachable. */
    fun nextHop(myId: UInt, dst: UInt, edges: List<TopoEdge>): UInt? {
        if (dst == myId) return null
        val adj = HashMap<UInt, MutableList<Pair<UInt, Double>>>()
        // Parallel links (e.g. WD + BLE) both go in; Dijkstra uses the cheaper one.
        for (e in edges) {
            adj.getOrPut(e.a) { mutableListOf() } += e.b to e.cost
            adj.getOrPut(e.b) { mutableListOf() } += e.a to e.cost
        }
        val dist = HashMap<UInt, Double>()
        val first = HashMap<UInt, UInt>()
        val queue = PriorityQueue<Pair<UInt, Double>>(compareBy({ it.second }, { it.first }))
        dist[myId] = 0.0
        queue += myId to 0.0
        while (queue.isNotEmpty()) {
            val (u, d) = queue.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue
            if (u == dst) return first[u]
            for ((v, c) in adj[u].orEmpty()) {
                val nd = d + c
                if (nd < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = nd
                    first[v] = if (u == myId) v else first.getValue(u)
                    queue += v to nd
                }
            }
        }
        return null
    }
}
