package com.chalkie.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.chalkie.app.AppGraph
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Foreground service that keeps the mesh and audio alive with the screen off (SPEC §17). */
class ChalkieService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: ChalkieSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var media: MediaSessionCompat? = null
    private var started = false
    private var micGranted = false
    private var pttReceiver: BroadcastReceiver? = null
    private var headsetDownMs = 0L
    private var headsetStartedTalk = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chalkie:session")
            .apply { setReferenceCounted(false); acquire() }

        scope.launch {
            val store = AppGraph.groupStore
            val group = store.group.first()
            if (group == null) {
                stopSelf()
                return@launch
            }
            val s = ChalkieSession(this@ChalkieService, group, store.identity(), scope)
            try {
                s.start()
            } catch (e: Exception) {
                Log.e(TAG, "session start failed", e)
                stopSelf()
                return@launch
            }
            session = s
            AppGraph.session.value = s
            launch(Dispatchers.Main) { setupPttInputs(s) }
            s.status.collect { st ->
                val text = "${st.groupName} · ${st.peers.size} peers" + (st.talkerName?.let { " · $it talking" } ?: "")
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
        return START_STICKY
    }

    /**
     * Android 14+ only lets a microphone foreground service start while the app is visible.
     * A sticky restart (after an update, or a crash) happens in the background: run as
     * connected-device only (still hears the team), and upgrade when the app is opened again
     * (the activity calls start() on resume, which lands here).
     */
    private fun goForeground(): Boolean {
        if (micGranted) return true
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Online"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            micGranted = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "microphone not allowed from the background yet: ${e.message}")
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification("Open Chalkie to enable talking"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }.isSuccess
        }
    }

    /**
     * PTT from outside the screen (SPEC §14 P4/P5):
     * - headset / Bluetooth button via a media session: short press toggles, long press is hold-to-talk;
     * - rugged phones' dedicated PTT keys, which their firmware reports as broadcasts.
     */
    private fun setupPttInputs(s: ChalkieSession) {
        media = MediaSessionCompat(this, "chalkie").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val ev = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                    if (ev.keyCode !in HEADSET_KEYS) return super.onMediaButtonEvent(intent)
                    onHeadsetKey(s, ev)
                    return true
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Any app can send these broadcasts: honour them only when the user opted in.
                if (!s.settings.hardwarePtt) return
                when (intent.action) {
                    in PTT_DOWN_ACTIONS -> s.pttDown()
                    in PTT_UP_ACTIONS -> s.pttUp()
                    KNOX_HARD_KEY -> when (intent.getIntExtra(KNOX_REPORT_TYPE, 0)) {
                        KNOX_PRESS -> s.pttDown()
                        KNOX_RELEASE -> s.pttUp()
                    }
                }
            }
        }
        val filter = IntentFilter().apply { (PTT_DOWN_ACTIONS + PTT_UP_ACTIONS + KNOX_HARD_KEY).forEach(::addAction) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        pttReceiver = receiver
    }

    private fun onHeadsetKey(s: ChalkieSession, ev: KeyEvent) {
        val now = SystemClock.uptimeMillis()
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) {
                if (s.node.isTalking) {
                    s.pttUp(); headsetStartedTalk = false
                } else {
                    headsetStartedTalk = s.pttDown(); headsetDownMs = now
                }
            }
            KeyEvent.ACTION_UP -> if (headsetStartedTalk && now - headsetDownMs > ChalkieConfig.HEADSET_TOGGLE_MAX_PRESS_MS) {
                s.pttUp(); headsetStartedTalk = false
            }
        }
    }

    override fun onDestroy() {
        media?.release()
        media = null
        pttReceiver?.let { runCatching { unregisterReceiver(it) } }
        pttReceiver = null
        AppGraph.session.value = null
        session?.let { s -> runBlocking { runCatching { s.stop() } } }
        session = null
        scope.cancel()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chalkie session", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ChalkieService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Chalkie")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "ChalkieService"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.chalkie.app.STOP"

        private val HEADSET_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
        /** PTT broadcasts sent by rugged phones' firmware (Kyocera/Sonim style and generic). */
        private val PTT_DOWN_ACTIONS = listOf(
            "android.intent.action.PTT.down", "com.sonim.intent.action.PTT_KEY_DOWN",
        )
        private val PTT_UP_ACTIONS = listOf(
            "android.intent.action.PTT.up", "com.sonim.intent.action.PTT_KEY_UP",
        )
        /** Samsung XCover / Tab Active key, when the device's Knox key mapping reports it to apps. */
        private const val KNOX_HARD_KEY = "com.samsung.android.knox.intent.action.HARD_KEY_REPORT"
        private const val KNOX_REPORT_TYPE = "com.samsung.android.knox.intent.extra.KEY_REPORT_TYPE"
        private const val KNOX_PRESS = 1
        private const val KNOX_RELEASE = 2

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ChalkieService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, ChalkieService::class.java))
    }
}
