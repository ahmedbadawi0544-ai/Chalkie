package com.chalkie.app

import android.app.Application
import android.content.Context
import com.chalkie.app.data.ChalkieDb
import com.chalkie.app.group.GroupStore
import com.chalkie.app.service.ChalkieSession
import kotlinx.coroutines.flow.MutableStateFlow

class ChalkieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/** Manual DI (SPEC §1): one place that owns the long-lived objects. */
object AppGraph {
    lateinit var context: Context
        private set

    val groupStore: GroupStore by lazy { GroupStore(context) }

    /** Logs and voice messages (Room). */
    val db: ChalkieDb by lazy { ChalkieDb.create(context) }

    /** The running session while ChalkieService is up, else null. */
    val session = MutableStateFlow<ChalkieSession?>(null)

    fun init(app: Application) {
        context = app.applicationContext
    }
}
