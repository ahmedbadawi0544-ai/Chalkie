package com.chalkie.app.group

import com.chalkie.app.core.ChalkieConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/** Everything a phone needs to join a group (SPEC §5). */
data class GroupInfo(
    val name: String,
    val secret: ByteArray,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    /** Custom channel names by index (SPEC §13); missing ones use [defaultChannelName]. */
    val channelNames: List<String> = emptyList(),
    /** This phone talks with supervisor priority (set by the supervisor variant of the QR). */
    val supervisor: Boolean = false,
) {
    fun channelName(i: Int): String = channelNames.getOrNull(i)?.ifBlank { null } ?: defaultChannelName(i)

    override fun equals(other: Any?): Boolean =
        other is GroupInfo && name == other.name && secret.contentEquals(other.secret) &&
            wifiSsid == other.wifiSsid && wifiPassword == other.wifiPassword &&
            channelNames == other.channelNames && supervisor == other.supervisor

    override fun hashCode(): Int = name.hashCode() * 31 + secret.contentHashCode()

    companion object {
        fun defaultChannelName(i: Int) = if (i == 0) "General" else "Channel $i"

        /** The creator (admin) is a supervisor. [channels] names channels 1, 2, … (0 is always General). */
        fun create(
            name: String,
            wifiSsid: String? = null,
            wifiPassword: String? = null,
            channels: List<String> = emptyList(),
        ): GroupInfo {
            val secret = ByteArray(ChalkieConfig.GROUP_SECRET_BYTES).also(SecureRandom()::nextBytes)
            val names = channels.map { it.trim().replace("|", "").take(ChalkieConfig.CHANNEL_NAME_MAX) }
                .filter { it.isNotEmpty() }.take(ChalkieConfig.CHANNEL_COUNT - 1)
            return GroupInfo(
                name, secret, wifiSsid?.ifBlank { null }, wifiPassword?.ifBlank { null },
                channelNames = if (names.isEmpty()) emptyList() else listOf(defaultChannelName(0)) + names,
                supervisor = true,
            )
        }
    }
}

/**
 * `chalkie://join?v=1&n=<name>&s=<base64url secret>&w=<ssid>&p=<password>&c=<ch0|ch1|…>&r=s`
 * (`r=s`: supervisor invite).
 */
object QrCodec {
    private const val PREFIX = "chalkie://join?"
    private const val VERSION = "1"

    fun encode(info: GroupInfo): String {
        val params = buildList {
            add("v" to VERSION)
            add("n" to info.name)
            add("s" to Base64.getUrlEncoder().withoutPadding().encodeToString(info.secret))
            info.wifiSsid?.let { add("w" to it) }
            info.wifiPassword?.let { add("p" to it) }
            if (info.channelNames.isNotEmpty()) add("c" to info.channelNames.joinToString("|"))
            if (info.supervisor) add("r" to "s")
        }
        return PREFIX + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** Null if the text is not a valid Chalkie join code. */
    fun decode(text: String): GroupInfo? {
        if (!text.startsWith(PREFIX)) return null
        val params = text.removePrefix(PREFIX).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to dec(part.substring(i + 1))
        }.toMap()
        if (params["v"] != VERSION) return null
        val name = params["n"]?.takeIf { it.isNotBlank() } ?: return null
        val secret = try {
            Base64.getUrlDecoder().decode(params["s"] ?: return null)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (secret.size != ChalkieConfig.GROUP_SECRET_BYTES) return null
        val channels = params["c"]?.split('|')?.take(ChalkieConfig.CHANNEL_COUNT).orEmpty()
        return GroupInfo(name, secret, params["w"], params["p"], channels, supervisor = params["r"] == "s")
    }

    // String charset overloads: the Charset ones are missing below Android 13.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
