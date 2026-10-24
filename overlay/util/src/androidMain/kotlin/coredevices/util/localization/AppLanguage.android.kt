package coredevices.util.localization

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

internal actual fun deviceLanguage(): String =
    Resources.getSystem().configuration.locales[0].toLanguageTag()

internal actual object LocalAppLocale {
    @Composable
    actual infix fun provides(language: String?): ProvidedValue<*> {
        val current = LocalConfiguration.current
        val locale = Locale.forLanguageTag(language ?: deviceLanguage())
        // Compose Resources 1.11 reads Locale.current from the process default.
        Locale.setDefault(locale)
        val configuration = remember(current, locale) {
            Configuration(current).apply { setLocale(locale) }
        }
        return LocalConfiguration provides configuration
    }
}
