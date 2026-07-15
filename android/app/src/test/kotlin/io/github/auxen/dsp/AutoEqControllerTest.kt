package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AutoEq split, Task 1: [AutoEqController] mirrors [EqController]'s object
 * shape but is persisted, applied, and toggled completely independently.
 * Its `importAutoEq`/failure-guarantee test mirrors [EqControllerTest]'s
 * only case (moved here since [EqController] no longer has `importAutoEq`
 * at all) -- unlike that original, this one calls [AutoEqController.initialize]
 * first, so "DataStore untouched" is a real proof persistence was AVOIDED,
 * not just an artifact of persistence never having been armed.
 */
@RunWith(RobolectricTestRunner::class)
class AutoEqControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        // Both are process-wide `object`s; wipe both singletons' in-memory
        // state AND the shared DataStore files so every test starts from a
        // clean slate regardless of JUnit method ordering (CI JDK-order
        // isolation fix -- EqController.resetForTest() didn't exist when
        // this file was first written, so it settled for a partial manual
        // reset that left appContext/initJob dangling between tests).
        AutoEqController.resetForTest()
        EqController.resetForTest()
        context.autoEqDataStore.edit { it.clear() }
        context.eqDataStore.edit { it.clear() }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        AutoEqController.resetForTest()
        EqController.resetForTest()
        Unit
    }

    @Test
    fun `state starts at defaults`() {
        assertEquals(EqState(), AutoEqController.state.value)
    }

    @Test
    fun `importAutoEq success updates state and persists to its own DataStore`() = runBlocking {
        AutoEqController.initialize(context)
        AutoEqController.awaitInitialized()

        val text = "Preamp: -3.0 dB\nFilter 1: ON PK Fc 1000 Hz Gain -2.0 dB Q 0.70\n"
        val result = AutoEqController.importAutoEq(text, "Test Profile")
        assertTrue("expected a successful Result", result.isSuccess)
        assertEquals("Test Profile", AutoEqController.state.value.presetName)
        assertEquals(1, AutoEqController.state.value.filters.size)
        assertNull("an AutoEq import must never carry graphic bands", AutoEqController.state.value.bands)

        AutoEqController.awaitPersisted()
        val stored = context.autoEqDataStore.data.first()[AutoEqController.KEY_STATE]
        assertTrue("expected the imported profile to be written to autoeq_state", stored != null)
    }

    @Test
    fun `a profile with a Q 0 line fails the import and never writes to DataStore`() = runBlocking {
        AutoEqController.initialize(context)
        AutoEqController.awaitInitialized()

        val result = AutoEqController.importAutoEq("Filter 1: ON PK Fc 105 Hz Gain -2.4 dB Q 0", "Bad Profile")
        assertTrue("expected a failed Result for an invalid Q", result.isFailure)

        val stored = context.autoEqDataStore.data.first()[AutoEqController.KEY_STATE]
        assertNull("expected no key ever written for a failed import", stored)
    }

    @Test
    fun `setEnabled toggles independently of the current filters`() {
        AutoEqController.importAutoEq(
            "Preamp: 0 dB\nFilter 1: ON PK Fc 500 Hz Gain 1.0 dB Q 0.70\n",
            "Some Profile",
        )
        assertTrue(AutoEqController.state.value.enabled) // import leaves it enabled
        AutoEqController.setEnabled(false)
        assertFalse(AutoEqController.state.value.enabled)
        assertEquals("Some Profile", AutoEqController.state.value.presetName)
        assertEquals(1, AutoEqController.state.value.filters.size)
    }

    @Test
    fun `clear resets to defaults, dropping filters and the profile name`() {
        AutoEqController.importAutoEq(
            "Preamp: 0 dB\nFilter 1: ON PK Fc 500 Hz Gain 1.0 dB Q 0.70\n",
            "Some Profile",
        )
        AutoEqController.clear()
        assertEquals(EqState(), AutoEqController.state.value)
    }

    // -- Migration: pre-split installs merged their AutoEq profile into
    // EqController's old combined eq_state (final review round successor,
    // AutoEq split Task 1) --

    @Test
    fun `migration moves an AutoEq-shaped legacy state into AutoEqController and resets EqController`() = runBlocking {
        // bands == null && filters.isNotEmpty() -- unambiguously an AutoEq
        // import under the old merged design (see EqState.fromBands: a
        // graphic state always carries bands).
        val legacyAutoEq = EqState(
            enabled = true,
            preampDb = -4.0,
            filters = listOf(FilterSpec(FilterType.PEAKING, 1000.0, 0.7, -2.0)),
            presetName = "Legacy Profile",
            bands = null,
        )
        EqController.setState(legacyAutoEq, persist = false)

        AutoEqController.initialize(context)
        AutoEqController.awaitInitialized()

        assertEquals(legacyAutoEq, AutoEqController.state.value)
        assertEquals(
            "the legacy graphic stage must reset to flat/disabled once migrated",
            EqState(),
            EqController.state.value,
        )
    }

    @Test
    fun `migration leaves a graphic-shaped legacy state in EqController and AutoEqController stays empty`() = runBlocking {
        val legacyGraphic = EqState.fromBands(List(EqState.NUM_BANDS) { 3.0 }, enabled = true, presetName = "Rock")
        EqController.setState(legacyGraphic, persist = false)

        AutoEqController.initialize(context)
        AutoEqController.awaitInitialized()

        assertEquals(EqState(), AutoEqController.state.value)
        assertEquals(legacyGraphic, EqController.state.value)
    }

    @Test
    fun `migration runs only once even if EqController state changes before a later initialize`() = runBlocking {
        val legacyAutoEq = EqState(
            enabled = true,
            filters = listOf(FilterSpec(FilterType.PEAKING, 500.0, 0.7, 3.0)),
            presetName = "First Migration",
            bands = null,
        )
        EqController.setState(legacyAutoEq, persist = false)
        AutoEqController.initialize(context)
        AutoEqController.awaitInitialized()
        // awaitInitialized() alone is now sufficient: migrateFromLegacyIfNeeded's
        // payload writes are directly-awaited DataStore#edit calls, not a
        // fire-and-forget setState(persist = true) (migration crash-safety
        // fix), so autoeq_state is already durable by the time it returns.
        // awaitPersisted() below is a no-op in this path (migration doesn't
        // set persistJob) -- kept rather than removed so this test doesn't
        // quietly start depending on migration's internals to stay correct.
        AutoEqController.awaitPersisted()
        assertEquals("First Migration", AutoEqController.state.value.presetName)

        // Simulate a fresh process start: AutoEqController's in-memory state
        // is gone, but the DataStore (including the migration marker) is
        // real and shared -- reset only the in-memory side, exactly what a
        // real process restart does.
        AutoEqController.resetForTest()
        // A DIFFERENT AutoEq-shaped state now sits in EqController -- if
        // migration ran again, it would wrongly overwrite the already-
        // migrated profile with this one.
        val secondLegacy = EqState(
            enabled = true,
            filters = listOf(FilterSpec(FilterType.PEAKING, 200.0, 0.7, 1.0)),
            presetName = "Should Not Migrate",
            bands = null,
        )
        EqController.setState(secondLegacy, persist = false)

        AutoEqController.initialize(context)
        AutoEqController.awaitInitialized()

        assertEquals(
            "the second initialize() must restore the ALREADY-migrated profile from AutoEqController's own DataStore, not re-migrate",
            "First Migration",
            AutoEqController.state.value.presetName,
        )
    }

    @Test
    fun `migration writes both payloads to disk before the guard is set, not just eventually`() = runBlocking {
        // Crash-safety regression (final review, Important #1 successor):
        // migrateFromLegacyIfNeeded used to setState(..., persist = true)
        // for both payloads -- fire-and-forget launches racing the awaited
        // KEY_MIGRATED write below them. A process kill after the guard
        // landed but before those launches flushed would permanently lose
        // the profile (guard true, autoeq_state absent) or double-apply it
        // (guard true, legacy eq_state never reset). Reading the RAW
        // DataStore values (not AutoEqController.state.value / EqController
        // .state.value, which setState() updates in-memory synchronously
        // regardless of whether the disk write has landed) is the only way
        // to actually prove the durability ordering, not just the
        // eventual in-memory outcome the other migration tests above check.
        val legacyAutoEq = EqState(
            enabled = true,
            preampDb = -4.0,
            filters = listOf(FilterSpec(FilterType.PEAKING, 1000.0, 0.7, -2.0)),
            presetName = "Crash Safety Profile",
            bands = null,
        )
        EqController.setState(legacyAutoEq, persist = false)

        AutoEqController.initialize(context)
        AutoEqController.awaitInitialized()

        val json = Json { ignoreUnknownKeys = true }

        val autoEqPrefs = context.autoEqDataStore.data.first()
        assertTrue(
            "expected the migration guard to already be set on disk",
            autoEqPrefs[AutoEqController.KEY_MIGRATED] == true,
        )

        val storedAutoEq = autoEqPrefs[AutoEqController.KEY_STATE]
        assertTrue("expected the AutoEq payload durable on disk by the time the guard is set", storedAutoEq != null)
        assertEquals(legacyAutoEq, json.decodeFromString<EqState>(storedAutoEq!!))

        val storedEq = context.eqDataStore.data.first()[EqController.KEY_STATE]
        assertTrue("expected the legacy eq_state reset durable on disk by the time the guard is set", storedEq != null)
        assertEquals(
            "legacy eq_state must already be reset to flat/disabled on disk, not just in memory",
            EqState(),
            json.decodeFromString<EqState>(storedEq!!),
        )
    }
}
