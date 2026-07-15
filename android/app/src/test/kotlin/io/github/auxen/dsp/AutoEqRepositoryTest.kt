package io.github.auxen.dsp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoEqRepositoryTest {

    private val repo = AutoEqRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun loadsBundledIndex() = runBlocking {
        repo.ensureLoaded()
        assertTrue("expected thousands of profiles, got ${repo.profileCount}", repo.profileCount > 5_000)
    }

    @Test
    fun searchFiltersCaseInsensitivelyAndCaps() = runBlocking {
        repo.ensureLoaded()
        val results = repo.search("sennheiser")
        assertTrue(results.isNotEmpty())
        assertTrue(results.size <= 50)
        assertTrue(results.all { it.name.contains("sennheiser", ignoreCase = true) })
        assertEquals(emptyList<AutoEqProfile>(), repo.search(""))
    }

    @Test
    fun profileTextParsesWithAutoEqParser() = runBlocking {
        repo.ensureLoaded()
        val profile = repo.search("HD 650").firstOrNull() ?: repo.search("a").first()
        val text = repo.profileText(profile)
        val state = AutoEqParser.parse(text, profile.name)
        assertTrue("expected filters in ${profile.name}", state.filters.isNotEmpty())
    }

    /**
     * Models the restore-on-start lookup in `AuxenApp.restoreAutoEqProfile`:
     * the persisted setting stores a profile's FULL name, and restore
     * searches for it again to re-resolve the [AutoEqProfile]. The known
     * name is picked dynamically (via a broad "Sennheiser" search) so the
     * test doesn't hardcode bundled-database contents.
     */
    @Test
    fun exactNameRoundTripsThroughSearch() = runBlocking {
        repo.ensureLoaded()
        val name = repo.search("Sennheiser").first().name
        assertEquals(name, repo.search(name).first().name)
    }
}
