package io.neta.music.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration

@get:SuppressLint("PrivateApi")
private val propertyGetter by lazy {
    runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
    }.getOrNull()
}

internal fun readSystemProperty(key: String): String? = runCatching {
    propertyGetter?.invoke(null, key, "") as? String
}.getOrNull()

internal val isNetaCar: Boolean by lazy {
    readSystemProperty("ro.hozon.car.code")?.trim()?.toIntOrNull() != null
}

fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature("android.software.leanback")
