package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Final-review fix round, Important #1 (import-level half): [AutoEqParser]
 * now rejects `Q 0`/`Fc 0` lines by throwing, and [EqController.importAutoEq]
 * already wraps the parse in `runCatching { ... }.onSuccess { setState(it) }`
 * -- so a thrown parse failure means `setState` (and therefore persistence)
 * is never reached. This test proves that end-to-end against the real
 * DataStore file, not just that `parse()` itself throws (see
 * [AutoEqParserTest] for that half).
 */
@RunWith(RobolectricTestRunner::class)
class EqControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        context.eqDataStore.edit { it.clear() }
        Unit
    }

    @Test
    fun `a profile with a Q 0 line fails the import and never writes to DataStore`() = runBlocking {
        val result = EqController.importAutoEq("Filter 1: ON PK Fc 105 Hz Gain -2.4 dB Q 0", "Bad Profile")
        assertTrue("expected a failed Result for an invalid Q", result.isFailure)

        val stored = context.eqDataStore.data.first()[EqController.KEY_STATE]
        assertNull("expected no key ever written for a failed import", stored)
    }
}
