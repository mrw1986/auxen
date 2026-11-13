package io.github.auxen.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchHistoryDaoTest {

    private lateinit var db: AuxenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun ordersNewestFirstAndDedupesByQuery() = runBlocking {
        db.searchHistoryDao().put(SearchHistoryEntity("radiohead", searchedAtMillis = 100))
        db.searchHistoryDao().put(SearchHistoryEntity("foo fighters", searchedAtMillis = 200))
        // Re-searching an old query bumps it to the top, no duplicate row.
        db.searchHistoryDao().put(SearchHistoryEntity("radiohead", searchedAtMillis = 300))

        assertEquals(
            listOf("radiohead", "foo fighters"),
            db.searchHistoryDao().recent(10).first().map { it.query },
        )
    }

    @Test
    fun respectsLimitDeleteAndClear() = runBlocking {
        for (i in 1..5) db.searchHistoryDao().put(SearchHistoryEntity("q$i", searchedAtMillis = i.toLong()))
        assertEquals(listOf("q5", "q4", "q3"), db.searchHistoryDao().recent(3).first().map { it.query })

        db.searchHistoryDao().delete("q5")
        assertEquals(listOf("q4", "q3", "q2", "q1"), db.searchHistoryDao().recent(10).first().map { it.query })

        db.searchHistoryDao().clear()
        assertTrue(db.searchHistoryDao().recent(10).first().isEmpty())
    }
}
