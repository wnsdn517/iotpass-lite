package dev.hoduj.iotpasslite

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** The colour scheme in use. Changing it redraws the screens in place, without recreating the activity. */
object AppTheme {
    val mode = mutableStateOf(ColorSchemeMode.System)

    fun modeOf(theme: String): ColorSchemeMode = when (theme) {
        Prefs.THEME_LIGHT -> ColorSchemeMode.Light
        Prefs.THEME_DARK -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }

    /** Loads the saved choice; call once when an activity starts. */
    fun load(context: Context) {
        mode.value = modeOf(Prefs.getTheme(context))
    }

    /** Saves the choice and applies it to the screens that are showing. */
    fun set(context: Context, theme: String) {
        Prefs.setTheme(context, theme)
        mode.value = modeOf(theme)
    }
}
