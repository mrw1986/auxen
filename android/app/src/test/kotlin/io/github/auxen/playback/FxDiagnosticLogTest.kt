package io.github.auxen.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Platform effects fix (user-confirmed device report, 2026-07-13): audibility
 * itself is un-CI-testable, so the shipped fallback is a structured
 * `Log.i("AuxenFx", ...)` line the user can grep for on-device
 * (`adb logcat -s AuxenFx`) to disambiguate every remaining hypothesis.
 * [formatFxDiagnosticLog] is the pure formatting half of that -- built from
 * plain data snapshots rather than live [android.media.audiofx.PresetReverb]/
 * [android.media.audiofx.Virtualizer] objects, so the exact field set and
 * wording are directly unit-testable without any platform-effect object or
 * Robolectric involvement (established: platform effect objects themselves
 * aren't Robolectric-testable).
 */
class FxDiagnosticLogTest {

    @Test
    fun `formats every field the device checklist depends on`() {
        val message = formatFxDiagnosticLog(
            sessionId = 42,
            reverb = ReverbDiagnostics(
                created = true,
                id = 7,
                hasControl = true,
                setEnabledStatus = 0,
                auxRouteSet = true,
            ),
            virtualizer = VirtualizerDiagnostics(
                created = true,
                strengthSupported = true,
                setStrengthStatus = 0,
                setEnabledStatus = 0,
                forceModeApplied = true,
            ),
        )
        assertEquals(
            "sessionId=42 " +
                "reverb[created=true id=7 hasControl=true setEnabledStatus=0 auxRouteSet=true] " +
                "virtualizer[created=true strengthSupported=true setStrengthStatus=0 setEnabledStatus=0 forceModeApplied=true]",
            message,
        )
    }

    @Test
    fun `formats the never-built case with null fields, not a crash`() {
        // The exact "nothing was ever constructed" case this log exists to
        // surface -- e.g. an emulator or device lacking the platform effect
        // implementation entirely (PresetReverb/Virtualizer's constructors
        // can throw; rebuildSessionEffects swallows that via runCatching).
        val message = formatFxDiagnosticLog(
            sessionId = -1,
            reverb = ReverbDiagnostics(
                created = false,
                id = null,
                hasControl = null,
                setEnabledStatus = null,
                auxRouteSet = false,
            ),
            virtualizer = VirtualizerDiagnostics(
                created = false,
                strengthSupported = null,
                setStrengthStatus = null,
                setEnabledStatus = null,
                forceModeApplied = false,
            ),
        )
        assertEquals(
            "sessionId=-1 " +
                "reverb[created=false id=null hasControl=null setEnabledStatus=null auxRouteSet=false] " +
                "virtualizer[created=false strengthSupported=null setStrengthStatus=null setEnabledStatus=null forceModeApplied=false]",
            message,
        )
    }
}
