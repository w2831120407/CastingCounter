package com.castingcounter.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CastingCounterApp : Application() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        setupUncaughtExceptionHandler()
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CastingCounterApp", "Uncaught exception: ${throwable.message}", throwable)
            // 尝试记录到SharedPreferences以便下次启动提示
            try {
                val prefs = getSharedPreferences("crash_log", MODE_PRIVATE)
                prefs.edit()
                    .putString("last_crash", throwable.message ?: "Unknown error")
                    .putLong("crash_time", System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // 交给默认处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun checkLastCrash(context: android.content.Context): String? {
            return try {
                val prefs = context.getSharedPreferences("crash_log", MODE_PRIVATE)
                val crashTime = prefs.getLong("crash_time", 0)
                val now = System.currentTimeMillis()
                // 只显示5分钟内的崩溃
                if (now - crashTime < 5 * 60 * 1000) {
                    prefs.getString("last_crash", null)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun clearCrashLog(context: android.content.Context) {
            try {
                val prefs = context.getSharedPreferences("crash_log", MODE_PRIVATE)
                prefs.edit().clear().apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
