package io.waves.data

import java.util.Locale

internal actual fun platformRegion(): String? = Locale.getDefault().country
