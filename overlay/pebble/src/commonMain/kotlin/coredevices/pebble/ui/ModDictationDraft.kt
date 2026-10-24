package coredevices.pebble.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.russhwolf.settings.Settings
import coredevices.util.localization.appText
import coredevices.util.transcription.DICTATION_ORIGINAL_KEY

@Composable
internal fun ModDictationDraftButton(settings: Settings) {
    var open by remember { mutableStateOf(false) }
    val clipboard=LocalClipboardManager.current
    TextButton(onClick={open=true}) { Text(appText("Last dictation original")) }
    if(open) {
        val text=settings.getString(DICTATION_ORIGINAL_KEY,"")
        AlertDialog(onDismissRequest={open=false},title={Text(appText("Last dictation original"))},
            text={SelectionContainer { Text(text.ifBlank { appText("No dictation draft yet") },
                modifier=Modifier.verticalScroll(rememberScrollState())) }},
            confirmButton={TextButton(onClick={clipboard.setText(AnnotatedString(text));open=false},enabled=text.isNotEmpty()) {
                Text(appText("Copy original"))
            }},dismissButton={TextButton(onClick={open=false}) { Text(appText("Close")) }})
    }
}
