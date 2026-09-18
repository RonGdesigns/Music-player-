package com.irondigital.spindle.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlayStat::class,
        PlayEvent::class,
        Favorite::class,
        Playlist::class,
        PlaylistItem::class,
        LyricsOverride::class,
        TrackGain::class,
        TrackEdit::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class SpindleDatabase : RoomDatabase() {

    abstract fun statsDao(): StatsDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun gainDao(): GainDao
    abstract fun trackEditDao(): TrackEditDao

    companion object {

        /**
         * Adds the ReplayGain cache. Written out by hand rather than falling back
         * to destructive migration, because this database holds the play counts —
         * the one thing in the app a user would genuinely mourn.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_gain` (
                        `mediaId` TEXT NOT NULL,
                        `trackGainDb` REAL,
                        `albumGainDb` REAL,
                        `trackPeak` REAL,
                        `albumPeak` REAL,
                        `scannedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** Adds the user's metadata corrections. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_edits` (
                        `mediaId` TEXT NOT NULL,
                        `title` TEXT,
                        `artist` TEXT,
                        `album` TEXT,
                        `year` INTEGER,
                        `trackNumber` INTEGER,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Adds the lyrics timing correction and the record of where a track's
         * lyrics came from. Both default in place, so existing rows stay valid
         * and nothing has to be rewritten.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `lyrics_overrides` ADD COLUMN `offsetMs` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `lyrics_overrides` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'USER'"
                )
            }
        }

        fun build(context: Context): SpindleDatabase =
            Room.databaseBuilder(context.applicationContext, SpindleDatabase::class.java, "spindle.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
