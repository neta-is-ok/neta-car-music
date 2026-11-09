package io.neta.music.ui.theme

import android.content.Context
import android.content.res.Configuration

fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature("android.software.leanback")
