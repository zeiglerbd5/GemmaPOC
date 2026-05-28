package com.zeiglerbd5.companion.gemmapoc

import android.content.Context
import com.zeiglerbd5.companion.gemmapoc.ui.theme.AppTheme

/**
 * Small wrapper around [android.content.SharedPreferences] for the user's
 * selected theme. Mirrors the iOS sibling's `@AppStorage("theme")` —
 * persists across launches, reads/writes are synchronous and cheap.
 *
 * DataStore would be more idiomatic on modern Android, but for one
 * string-typed preference it adds a dep + a Flow + coroutine plumbing
 * for no real benefit. Revisit if this preferences surface grows.
 */
class ThemePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var theme: AppTheme
        get() = AppTheme.fromName(prefs.getString(KEY_THEME, null))
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    /** "In Depth" toggle — thorough answers when on, concise when off. */
    var detailed: Boolean
        get() = prefs.getBoolean(KEY_DETAILED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DETAILED, value).apply()
        }

    private companion object {
        const val FILE_NAME = "ui_preferences"
        const val KEY_THEME = "theme"
        const val KEY_DETAILED = "detailed"
    }
}
