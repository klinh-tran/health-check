package com.example.healthcheck.util

import android.util.Log
import com.example.healthcheck.BuildConfig

/**
 * Debug-only logging. Messages must never contain personal values (heart rate, HRV, steps...).
 * In release builds nothing is logged.
 */
object DebugLog {
    private const val PREFIX = "HealthCheck/"

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(PREFIX + tag, message, t)
    }
}
