package coredevices.util.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import coredevices.util.Permission

object AppLanguage {
    val choices = listOf("system", "zh-CN", "ja", "en")
    var selected by mutableStateOf("system")
        private set

    fun initialize(settings: Settings) {
        selected = settings.getString("mod.appLanguage", "system").takeIf { it in choices } ?: "system"
    }

    fun select(settings: Settings, language: String) {
        require(language in choices)
        settings.putString("mod.appLanguage", language)
        selected = language
    }

    internal fun resolved() = if (selected == "system") deviceLanguage() else selected

    fun label(language: String): String = when (language) {
        "zh-CN" -> "简体中文"
        "ja" -> "日本語"
        "en" -> "English"
        else -> appText("Follow system")
    }
}

private val argumentPattern = Regex("%(\\d+)\\\$s")

fun appText(english: String, vararg arguments: Any?): String {
    val translated = appCatalog[english]
    val language = AppLanguage.resolved()
    val template = when {
        language.startsWith("zh", ignoreCase = true) -> translated?.first
        language.startsWith("ja", ignoreCase = true) -> translated?.second
        else -> null
    } ?: translated?.third ?: english
    return argumentPattern.replace(template) { match ->
        val index = match.groupValues[1].toInt() - 1
        if (index !in arguments.indices) {
            match.value
        } else {
            when (val value = arguments[index]) {
                is Permission -> appText(value.name)
                else -> value.toString()
            }
        }
    }
}

@Composable
fun AppLanguageEnvironment(settings: Settings, content: @Composable () -> Unit) {
    remember(settings) { AppLanguage.initialize(settings) }
    val selected = AppLanguage.selected
    CompositionLocalProvider(LocalAppLocale provides selected.takeUnless { it == "system" }) {
        key(selected, AppLanguage.resolved()) { content() }
    }
}

internal expect fun deviceLanguage(): String

internal expect object LocalAppLocale {
    @Composable
    infix fun provides(language: String?): ProvidedValue<*>
}
