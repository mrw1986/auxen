package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore round-trip coverage for the Bit-Perfect / Direct toggle, mirroring
 * [AudioFxControllerTest]'s per-effect persistence tests and the themeMode
 * settings round-trip. The live player rebuild the flag drives is integration-
 * level (a real MediaSession + ExoPlayer), so it is NOT covered here — only the
 * flag's persistence/restore, which is the JVM-unit-testable half.
 */
@RunWith(RobolectricTestRunner::class)
class BitPerfectControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        // BitPerfectController is a process-wide `object`; wipe both its
        // in-memory state and the DataStore file so each test starts clean
        // regardless of JUnit method ordering.
        BitPerfectController.resetForTest()
        context.bitPerfectDataStore.edit { it.clear() }
        Unit
    }

    @Test
    fun `defaults to false before initialize`() {
        assertFalse(BitPerfectController.enabled.value)
    }

    @Test
    fun `enabling persists across a fresh initialize`() = runBlocking {
        BitPerfectController.initialize(context)
        BitPerfectController.awaitInitialized()

        BitPerfectController.setEnabled(true)
        BitPerfectController.awaitPersisted()

        // Drop all in-memory state, then reload purely from DataStore.
        BitPerfectController.resetForTest()
        assertFalse("reset drops in-memory state", BitPerfectController.enabled.value)

        BitPerfectController.initialize(context)
        BitPerfectController.awaitInitialized()
        assertTrue("restored true from DataStore", BitPerfectController.enabled.value)
    }

    @Test
    fun `disabling after enabling persists across a fresh initialize`() = runBlocking {
        BitPerfectController.initialize(context)
        BitPerfectController.awaitInitialized()
        BitPerfectController.setEnabled(true)
        BitPerfectController.awaitPersisted()
        BitPerfectController.setEnabled(false)
        BitPerfectController.awaitPersisted()

        BitPerfectController.resetForTest()
        BitPerfectController.initialize(context)
        BitPerfectController.awaitInitialized()
        assertFalse("restored false from DataStore", BitPerfectController.enabled.value)
    }

    @Test
    fun `enabled flow emits the new value on setEnabled`() = runBlocking {
        BitPerfectController.initialize(context)
        BitPerfectController.awaitInitialized()

        assertFalse(BitPerfectController.enabled.value)
        BitPerfectController.setEnabled(true)
        assertTrue(BitPerfectController.enabled.value)
        BitPerfectController.setEnabled(false)
        assertFalse(BitPerfectController.enabled.value)
    }
}
