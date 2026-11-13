package io.github.auxen

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * Platform effects fix (user-confirmed device report, 2026-07-13): routing
 * reverb as an auxiliary/send effect via `Player.setAuxEffectInfo` requires
 * `MODIFY_AUDIO_SETTINGS` (a normal permission, no runtime request needed --
 * but still declared, or the aux route silently fails on some OEMs).
 */
@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {

    @Test
    fun `manifest declares MODIFY_AUDIO_SETTINGS`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "expected MODIFY_AUDIO_SETTINGS in the manifest, got $requested",
            requested.contains(Manifest.permission.MODIFY_AUDIO_SETTINGS),
        )
    }

    /** The track cache watches the default network; registerDefaultNetworkCallback needs this. */
    @Test
    fun `manifest declares ACCESS_NETWORK_STATE`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "expected ACCESS_NETWORK_STATE in the manifest, got $requested",
            requested.contains(Manifest.permission.ACCESS_NETWORK_STATE),
        )
    }
}
