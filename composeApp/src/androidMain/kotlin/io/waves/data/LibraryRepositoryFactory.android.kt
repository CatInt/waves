package io.waves.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.waves.data.db.WavesDatabase

/** Opens (creating/migrating as needed) the app database `waves.db`. */
fun createLibraryRepository(context: Context): SqlLibraryRepository =
    SqlLibraryRepository(AndroidSqliteDriver(WavesDatabase.Schema, context.applicationContext, DATABASE_NAME))

const val DATABASE_NAME = "waves.db"
