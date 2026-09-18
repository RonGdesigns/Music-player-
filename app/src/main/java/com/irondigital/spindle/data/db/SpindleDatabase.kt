package com.irondigital.spindle.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlayStat::class,
        PlayEvent::class,
        Favorite::class,
        Playlist::class,
        PlaylistItem::class,
        LyricsOverride::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SpindleDatabase : RoomDatabase() {

    abstract fun statsDao(): StatsDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        fun build(context: Context): SpindleDatabase =
            Room.databaseBuilder(context.applicationContext, SpindleDatabase::class.java, "spindle.db")
                .build()
    }
}
