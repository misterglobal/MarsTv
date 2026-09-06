package tv.mars.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.data.network.M3uBatch

@RunWith(AndroidJUnit4::class)
class RoomCatalogPreviewTest {
    private lateinit var database: MarsTvDatabase
    private lateinit var store: RoomCatalogStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MarsTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomCatalogStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun firstBatchIsVisibleAndDiscardRemovesPreview() = runBlocking {
        val accountId = "preview-account"
        val category = Category("live:news", "news", "News", ContentKind.LIVE)
        val channel = Channel(
            key = "$accountId:live:1",
            remoteId = "1",
            accountId = accountId,
            name = "News One",
            categoryKey = category.key,
            categoryName = category.name,
            logoUrl = "",
            epgId = "news-one",
            playbackUrl = "https://example.com/live/1.ts",
        )
        val session = store.beginImport(accountId, publishEarly = true)

        session.write(M3uBatch(categories = listOf(category), channels = listOf(channel)))

        assertTrue(store.hasCatalog(accountId))
        assertEquals(0L, store.catalogLoadedAt(accountId))
        assertEquals(listOf(category), store.observeCategories(accountId, ContentKind.LIVE).first())

        session.discard()

        assertFalse(store.hasCatalog(accountId))
    }
}
