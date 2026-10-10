package net.wastu.cleancopy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseSourceDialog(onDismiss: () -> Unit, onChooseMedia: () -> Unit, onCleanUrl: (String) -> Unit) {
    var showInput by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val valid = runCatching {
        val link = LinkSanitizer.firstLink(input.trim()) ?: return@runCatching false
        URI(link).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
    }.getOrDefault(false)
    fun submit() {
        submitted = true
        if (valid) { onDismiss(); onCleanUrl(input.trim()) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (showInput) "Clean a link" else "Choose content to clean", style = MaterialTheme.typography.titleLarge)
            if (!showInput) {
                FilledTonalButton(onClick = { onDismiss(); onChooseMedia() }, modifier = Modifier.fillMaxWidth()) { Text("Choose photos or videos") }
                OutlinedButton(onClick = { showInput = true }, modifier = Modifier.fillMaxWidth()) { Text("Enter or paste a link") }
            } else {
                OutlinedTextField(value = input, onValueChange = { input = it; submitted = false },
                    label = { Text("Web link") }, placeholder = { Text("https://example.com") },
                    supportingText = { if (submitted && !valid) Text("Enter an HTTP or HTTPS link") },
                    isError = submitted && !valid, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
                Button(onClick = ::submit, modifier = Modifier.fillMaxWidth()) { Text("Clean link") }
                TextButton(onClick = { showInput = false }) { Text("Back to sources") }
            }
        }
    }
}
