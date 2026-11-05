package com.focusfeed.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FocusStats {
    private const val PREFS = "focusfeed_stats"
    private const val KEY_TOTAL = "blocks_total"
    private const val KEY_TODAY_DATE = "blocks_today_date"
    private const val KEY_TODAY_COUNT = "blocks_today_count"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun recordBlock(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = dateFmt.format(Date())
        val storedDate = prefs.getString(KEY_TODAY_DATE, "")
        val todayCount = if (storedDate == today) prefs.getInt(KEY_TODAY_COUNT, 0) else 0
        prefs.edit()
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_COUNT, todayCount + 1)
            .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
            .apply()
    }

    fun todayCount(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = dateFmt.format(Date())
        return if (prefs.getString(KEY_TODAY_DATE, "") == today)
            prefs.getInt(KEY_TODAY_COUNT, 0) else 0
    }

    fun totalCount(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TOTAL, 0)

    // 23 seconds average per intercepted scroll session
    fun minutesSaved(context: Context): Long = totalCount(context) * 23L / 60L
}
