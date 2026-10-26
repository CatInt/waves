package io.waves

import android.os.Build

actual val platformName: String = "Android ${Build.VERSION.RELEASE}"
actual fun currentTimeMillis(): Long = System.currentTimeMillis()
