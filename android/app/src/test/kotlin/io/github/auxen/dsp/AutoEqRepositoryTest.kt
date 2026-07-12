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
}
