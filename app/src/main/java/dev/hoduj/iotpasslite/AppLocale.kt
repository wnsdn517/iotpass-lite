package dev.hoduj.iotpasslite

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Applies the language chosen in settings. "system" follows the device: Korean when the device is Korean,
 *  English for every other language. */
object AppLocale {

    fun wrap(base: Context): Context {
        val locale = when (Prefs.getLanguage(base)) {
            Prefs.LANG_EN -> Locale.ENGLISH
            Prefs.LANG_KO -> Locale.KOREAN
            else -> if (base.resources.configuration.locales[0].language == "ko") Locale.KOREAN else Locale.ENGLISH
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
