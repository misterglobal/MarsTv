package tv.mars.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CatalogImportEntity::class,
        CatalogCategoryEntity::class,
        CatalogItemEntity::class,
        CatalogEpisodeEntity::class,
        CatalogProgrammeEntity::class,
    ],
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing catalog URLs may contain provider credentials. Purge the cache once;
                // the encrypted account configuration remains and the catalog is re-imported.
                db.execSQL("DELETE FROM catalog_programmes")
                db.execSQL("DELETE FROM catalog_episodes")
                db.execSQL("DELETE FROM catalog_items")
                db.execSQL("DELETE FROM catalog_categories")
                db.execSQL("DELETE FROM catalog_imports")
            }
        }
    }
}
