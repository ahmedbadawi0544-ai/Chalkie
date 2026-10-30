package com.chalkie.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chalkie.app.AppGraph
import com.chalkie.app.data.LogEntry
import com.chalkie.app.data.VoiceMessageEntity
import com.chalkie.app.group.GroupInfo
import com.chalkie.app.group.QrCodec
import com.chalkie.app.group.Settings
import com.chalkie.app.routing.TopoEdge
import com.chalkie.app.service.ChalkieService
import com.chalkie.app.service.ChatLine
import com.chalkie.app.service.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Loading = group not read from storage yet. */
sealed interface GroupState {
    data object Loading : GroupState
    data object None : GroupState
    data class Joined(val info: GroupInfo) : GroupState
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChalkieViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppGraph.groupStore
    private val db = AppGraph.db
    private val session get() = AppGraph.session.value

    val group: StateFlow<GroupState> = store.group
        .map { if (it == null) GroupState.None else GroupState.Joined(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroupState.Loading)

    val name: StateFlow<String> = store.name.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val running: StateFlow<Boolean> = AppGraph.session.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val status: StateFlow<SessionStatus?> = AppGraph.session
        .flatMapLatest { it?.status ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val chat: StateFlow<List<ChatLine>> = AppGraph.session
        .flatMapLatest { it?.chat ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val log: StateFlow<List<LogEntry>> = db.log().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val voiceMessages: StateFlow<List<VoiceMessageEntity>> = db.voiceMessages().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True while the settings screen waits for the user to press the key to use as PTT. */
    val learningKey = MutableStateFlow(false)

    fun setName(name: String) = viewModelScope.launch { store.setName(name) }

    fun createGroup(name: String, wifiSsid: String = "", wifiPassword: String = "", channels: String = "") =
        viewModelScope.launch {
            store.save(GroupInfo.create(name.trim(), wifiSsid.trim(), wifiPassword, channels.split(',')))
        }

    /** Returns false if [qrText] is not a Chalkie join code. */
    fun joinGroup(qrText: String): Boolean {
        val info = QrCodec.decode(qrText) ?: return false
        viewModelScope.launch { store.save(info) }
        return true
    }

    fun leaveGroup() = viewModelScope.launch {
        ChalkieService.stop(getApplication())
        store.clear()
    }

    fun startService() = ChalkieService.start(getApplication())
    fun stopService() = ChalkieService.stop(getApplication())

    // ---- talk ----

    fun pttDown(target: UInt? = null): Boolean = session?.pttDown(target) ?: false
    fun pttUp() { session?.pttUp() }
    fun setChannel(channel: Int) { session?.setChannel(channel) }
    fun sendText(text: String, target: UInt? = null): Boolean = session?.sendText(text, target) ?: false

    // ---- voice messages ----

    fun startRecording(): Boolean = session?.startRecording() ?: false
    fun finishRecording(to: UInt): Boolean = session?.finishRecording(to) ?: false
    fun cancelRecording() { session?.cancelRecording() }
    fun play(m: VoiceMessageEntity) { session?.playVoiceMessage(m) }
    fun delete(m: VoiceMessageEntity) {
        session?.deleteVoiceMessage(m.uid) ?: viewModelScope.launch { db.voiceMessages().delete(m.uid) }
    }

    // ---- map ----

    fun topology(): List<TopoEdge> = session?.topology().orEmpty()

    // ---- settings ----

    fun setPttKey(code: Int) = viewModelScope.launch { store.setPttKey(code) }
    fun setUseOpus(on: Boolean) = viewModelScope.launch { store.setUseOpus(on) }
    fun setHardwarePtt(on: Boolean) = viewModelScope.launch { store.setHardwarePtt(on) }
    fun setQuickTexts(texts: List<String>) = viewModelScope.launch { store.setQuickTexts(texts) }

    // ---- logs ----

    fun clearLog() = viewModelScope.launch { db.log().clear() }

    /** Writes the whole log as CSV to [uri] (SPEC §13). */
    fun exportCsv(uri: Uri, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                    w.write("time,kind,who,channel,duration_s,details\n")
                    db.log().all().forEach { e ->
                        val cols = listOf(
                            fmt.format(Date(e.timeMs)), e.kind, e.who, e.channel?.toString() ?: "",
                            e.durationMs?.let { "%.1f".format(Locale.US, it / 1000.0) } ?: "", e.text ?: "",
                        )
                        w.write(cols.joinToString(",") { csv(it) })
                        w.write("\n")
                    }
                }
            }.isSuccess
        }
        done(ok)
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
