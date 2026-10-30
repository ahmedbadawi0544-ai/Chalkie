package com.chalkie.app.group

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chalkie.app.core.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.store by preferencesDataStore("chalkie")

/** Local identity of this phone. nodeId is generated once and kept forever (SPEC §2). */
data class Identity(val nodeId: UInt, val name: String)

/** Per-phone preferences. */
data class Settings(
    val channel: Int = 0,
    /** Android key code used as PTT while the app is open (0 = off), e.g. volume-down or a rugged phone's side key. */
    val pttKeyCode: Int = 0,
    val useOpus: Boolean = true,
    /** Listen for rugged phones' PTT-key broadcasts (off by default: any app could send them). */
    val hardwarePtt: Boolean = false,
    val quickTexts: List<String> = DEFAULT_QUICK_TEXTS,
) {
    companion object {
        val DEFAULT_QUICK_TEXTS = listOf("Arrived at FDB", "Cut power", "Need help", "Done")
    }
}

/**
 * Group membership + identity, in DataStore. The join code (which holds the group secret)
 * is encrypted with an AES key that never leaves the Android Keystore (SPEC §5).
 */
class GroupStore(private val context: Context) {
    private val kGroup = stringPreferencesKey("group_enc")
    private val kNodeId = longPreferencesKey("node_id")
    private val kName = stringPreferencesKey("name")
    private val kChannel = intPreferencesKey("channel")
    private val kPttKey = intPreferencesKey("ptt_key")
    private val kOpus = booleanPreferencesKey("opus")
    private val kHwPtt = booleanPreferencesKey("hw_ptt")
    private val kQuick = stringPreferencesKey("quick_texts")

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            channel = p[kChannel] ?: 0,
            pttKeyCode = p[kPttKey] ?: 0,
            useOpus = p[kOpus] ?: true,
            hardwarePtt = p[kHwPtt] ?: false,
            quickTexts = p[kQuick]?.split('\n')?.filter { it.isNotBlank() } ?: Settings.DEFAULT_QUICK_TEXTS,
        )
    }

    suspend fun setChannel(channel: Int) = context.store.edit { it[kChannel] = channel }
    suspend fun setPttKey(code: Int) = context.store.edit { it[kPttKey] = code }
    suspend fun setUseOpus(on: Boolean) = context.store.edit { it[kOpus] = on }
    suspend fun setHardwarePtt(on: Boolean) = context.store.edit { it[kHwPtt] = on }
    suspend fun setQuickTexts(texts: List<String>) =
        context.store.edit { it[kQuick] = texts.map { t -> t.replace('\n', ' ').trim() }.filter { t -> t.isNotEmpty() }.joinToString("\n") }

    val group: Flow<GroupInfo?> = context.store.data.map { prefs ->
        prefs[kGroup]?.let { runCatching { QrCodec.decode(decrypt(it)) }.getOrNull() }
    }

    val name: Flow<String> = context.store.data.map { it[kName] ?: Build.MODEL.take(24) }

    suspend fun identity(): Identity {
        var id: UInt? = null
        context.store.edit { prefs ->
            val existing = prefs[kNodeId]
            id = if (existing != null) existing.toUInt() else Ids.newNodeId().also { prefs[kNodeId] = it.toLong() }
        }
        return Identity(id!!, name.first())
    }

    suspend fun setName(name: String) {
        context.store.edit { it[kName] = name.trim().take(24) }
    }

    suspend fun save(info: GroupInfo) {
        val enc = encrypt(QrCodec.encode(info))
        context.store.edit { it[kGroup] = enc }
    }

    suspend fun clear() {
        context.store.edit { it.remove(kGroup) }
    }

    // ---- Keystore wrapping ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        return c.doFinal(raw, IV_LEN, raw.size - IV_LEN).toString(Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "chalkie_group_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
