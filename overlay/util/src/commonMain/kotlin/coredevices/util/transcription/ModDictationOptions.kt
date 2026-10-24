package coredevices.util.transcription

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings

object ModDictationOptions {
    var polish by mutableStateOf(true)
        private set
    fun initialize(settings: Settings) { polish=settings.getBoolean(DICTATION_POLISH_KEY,true) }
    fun select(settings: Settings, enabled: Boolean) {
        settings.putBoolean(DICTATION_POLISH_KEY,enabled);polish=enabled
    }
}
