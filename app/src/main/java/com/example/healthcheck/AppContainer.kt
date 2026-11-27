package com.example.healthcheck

import android.app.Application
import android.content.Context
import com.example.healthcheck.data.AppDatabase
import com.example.healthcheck.data.EntryRepository
import com.example.healthcheck.data.SettingsRepository

/** Tiny manual dependency container; one per process. */
class AppContainer(context: Context) {
    private val database by lazy { AppDatabase.create(context) }
    val entries by lazy { EntryRepository(database.dailyEntryDao(), compact = { database.compact() }) }
    val settings by lazy { SettingsRepository(context) }
}

class HealthCheckApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as HealthCheckApp).container
