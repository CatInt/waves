package io.waves

actual val platformName: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
actual fun currentTimeMillis(): Long = System.currentTimeMillis()
