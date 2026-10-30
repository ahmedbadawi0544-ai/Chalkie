package com.chalkie.app.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: ChalkieViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ChalkieTheme { ChalkieRoot(vm) } }
    }

    /**
     * PTT on a hardware key while the app is open (SPEC §14 P4/P5): volume key, or a rugged
     * phone's side key (e.g. Samsung XCover / Tab Active key) learned in Settings.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event)
        }
        if (vm.learningKey.value) {
            if (event.action == KeyEvent.ACTION_UP) {
                vm.setPttKey(code)
                vm.learningKey.value = false
            }
            return true
        }
        val ptt = vm.settings.value.pttKeyCode
        if (ptt != 0 && code == ptt && vm.running.value) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) vm.pttDown()
                KeyEvent.ACTION_UP -> vm.pttUp()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

val Amber = Color(0xFFF2A900)
val TalkRed = Color(0xFFE5484D)
val ListenGreen = Color(0xFF30A46C)

@Composable
fun ChalkieTheme(content: @Composable () -> Unit) {
    // Field app: always dark, high contrast.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color(0xFF101418),
            background = Color(0xFF101418),
            surface = Color(0xFF181D23),
            surfaceVariant = Color(0xFF222932),
        ),
        content = content,
    )
}
