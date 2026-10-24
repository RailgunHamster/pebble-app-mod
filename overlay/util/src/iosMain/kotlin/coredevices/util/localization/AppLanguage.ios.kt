package coredevices.util.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

private val originalLanguage = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
private val localLanguage = staticCompositionLocalOf { originalLanguage }

internal actual fun deviceLanguage(): String = originalLanguage

internal actual object LocalAppLocale {
    @Composable
    actual infix fun provides(language: String?): ProvidedValue<*> {
        if (language == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey("AppleLanguages")
        } else {
            NSUserDefaults.standardUserDefaults.setObject(listOf(language), "AppleLanguages")
        }
        return localLanguage provides (language ?: originalLanguage)
    }
}
