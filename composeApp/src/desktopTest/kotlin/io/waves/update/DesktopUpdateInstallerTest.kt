package io.waves.update

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopUpdateInstallerTest {
    private fun release(vararg names: String) = AppRelease(
        Version(1, 0, 1), "", "", names.map { ReleaseAsset(it, "https://example.com/$it", 1) },
    )

    @Test
    fun picksTheDmgForThisMacsArchitecture() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        val installer = DesktopUpdateInstaller(quitApp = {})
        val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
        val picked = installer.assetFor(release("waves-1.0.1.apk", "Waves-1.0.1-x64.dmg", "Waves-1.0.1-arm64.dmg"))
        assertEquals(if (arm) "Waves-1.0.1-arm64.dmg" else "Waves-1.0.1-x64.dmg", picked?.name)
        assertEquals("Waves-1.0.1.dmg", installer.assetFor(release("Waves-1.0.1.dmg"))?.name)
        val other = if (arm) "Waves-1.0.1-x64.dmg" else "Waves-1.0.1-arm64.dmg"
        assertEquals(null, installer.assetFor(release(other)))
    }
}
