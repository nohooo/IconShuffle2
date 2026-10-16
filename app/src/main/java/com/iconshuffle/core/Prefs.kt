package com.iconshuffle.core

import android.content.Context
import android.content.SharedPreferences

/** 用户可见的全部设置。用 SharedPreferences 就够，不引入 DataStore。 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("icon_shuffle", Context.MODE_PRIVATE)

    var scope: ShuffleScope
        get() = ShuffleScope.fromName(sp.getString(KEY_SCOPE, null))
        set(v) = sp.edit().putString(KEY_SCOPE, v.name).apply()

    var autoEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    /** 自动打乱的执行时间，单位「当天第几分钟」，默认 07:30。 */
    var autoMinuteOfDay: Int
        get() = sp.getInt(KEY_AUTO_MINUTE, DEFAULT_MINUTE)
        set(v) = sp.edit().putInt(KEY_AUTO_MINUTE, v.coerceIn(0, 24 * 60 - 1)).apply()

    /** 是否保护 Dock 栏（默认保护）。 */
    var keepDock: Boolean
        get() = sp.getBoolean(KEY_KEEP_DOCK, true)
        set(v) = sp.edit().putBoolean(KEY_KEEP_DOCK, v).apply()

    /** 上次成功打乱的时间戳，用于「一天只打乱一次」的兜底判断。 */
    var lastShuffleAt: Long
        get() = sp.getLong(KEY_LAST_SHUFFLE, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_SHUFFLE, v).apply()

    var lastResult: String
        get() = sp.getString(KEY_LAST_RESULT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_RESULT, v).apply()

    companion object {
        private const val KEY_SCOPE = "scope"
        private const val KEY_AUTO = "auto_enabled"
        private const val KEY_AUTO_MINUTE = "auto_minute_of_day"
        private const val KEY_KEEP_DOCK = "keep_dock"
        private const val KEY_LAST_SHUFFLE = "last_shuffle_at"
        private const val KEY_LAST_RESULT = "last_result"

        const val DEFAULT_MINUTE = 7 * 60 + 30 // 07:30

        fun formatMinuteOfDay(minute: Int): String =
            "%02d:%02d".format(minute / 60, minute % 60)
    }
}
