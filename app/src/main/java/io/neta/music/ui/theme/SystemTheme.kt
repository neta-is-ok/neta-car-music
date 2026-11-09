package io.neta.music.ui.theme

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.neta.music.MyMusicApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemThemeState(val dark: Boolean, val fromOem: Boolean)

/** 进程内共享系统主题；OEM 通知只触发重读，用户的深浅色偏好仍由 Appearance 决定。 */
class SystemTheme(private val context: Context) {
    private val current = MutableStateFlow(read(context))
    val state = current.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == ACTION_THEME_CHANGED) refresh()
                }
            },
            IntentFilter(ACTION_THEME_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = refresh()
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        refresh()
    }

    fun refresh() {
        current.value = read(context)
    }

    companion object {
        private const val ACTION_THEME_CHANGED = "com.hozonauto.thememanager.ACTION_HOZON_THEME_CHANGED"

        @get:SuppressLint("PrivateApi")
        private val propertyGetter by lazy {
            runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            }.getOrNull()
        }

        private fun property(key: String): String? = runCatching {
            propertyGetter?.invoke(null, key, "") as? String
        }.getOrNull()

        fun read(context: Context): SystemThemeState {
            val oemNight = when (property("persist.sys.theme_night_mode")) {
                "night_mode_on" -> true
                "night_mode_off" -> false
                "night_mode_auto" -> when (property("persist.sys.theme.from.map")) {
                    "night_mode" -> true
                    "day_mode" -> false
                    else -> null
                }
                else -> null
            }
            val androidNight = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return SystemThemeState(oemNight ?: androidNight, fromOem = oemNight != null)
        }
    }
}

@Composable
fun isAppSystemInDarkTheme(): Boolean {
    val app = LocalContext.current.applicationContext as MyMusicApp
    val theme by app.systemTheme.state.collectAsState()
    return theme.dark
}
