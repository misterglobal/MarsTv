package tv.mars.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CatalogImportEntity::class,
        CatalogCategoryEntity::class,
        CatalogItemEntity::class,
        CatalogEpisodeEntity::class,
        CatalogProgrammeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MarsTvDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile private var instance: MarsTvDatabase? = null

        fun getInstance(context: Context): MarsTvDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MarsTvDatabase::class.java,
                "marstv.db",
            ).build().also { instance = it }
        }
    }
}
