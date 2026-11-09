package com.toolbox.multitool.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.ToolScaffold

/**
 * Makes mixed Persian/English text render right-to-left everywhere (chat apps, Telegram, notes)
 * by embedding Unicode bidi controls. No length limit: it is a single linear pass per line.
 */
object RtlFixer {
    private const val RLI = '\u2067' // right-to-left isolate
    private const val LRI = '\u2066' // left-to-right isolate
    private const val PDI = '\u2069' // pop directional isolate

    // Latin/number phrases: words joined by single spaces stay one LTR unit, so
    // "Hello World 2026" keeps its internal order inside the RTL sentence.
    private val ltrToken = "[A-Za-z0-9][A-Za-z0-9@#_./:+\\-'%&=?]*"
    private val ltrRun = Regex("$ltrToken(?: $ltrToken)*")

    private val bidiControls = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")

    fun fix(input: String, normalizeArabicLetters: Boolean = true, persianDigits: Boolean = false): String {
        var text = input.replace("\r\n", "\n")
        text = bidiControls.replace(text, "") // idempotent: strip earlier controls first
        if (normalizeArabicLetters) text = text.replace('ي', 'ی').replace('ك', 'ک')
        if (persianDigits) text = text.map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")

        return text.split('\n').joinToString("\n") { line ->
            if (line.isBlank()) line
            else {
                val wrapped = ltrRun.replace(line) { "$LRI${it.value}$PDI" }
                "$RLI$wrapped$PDI"
            }
        }
    }
}

@Composable
fun RtlScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var normalize by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(false) }
    val output = remember(input, normalize, digits) { RtlFixer.fix(input, normalize, digits) }

    ToolScaffold("Persian/English fixer", onBack) {
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            label = { Text("Paste text") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        )
        Row { Switch(normalize, { normalize = it }); Spacer(Modifier.width(8.dp)); Text("Fix Arabic ي ك → ی ک") }
        Row { Switch(digits, { digits = it }); Spacer(Modifier.width(8.dp)); Text("Latin digits → Persian digits") }
        Text("Result", style = MaterialTheme.typography.titleSmall)
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(
                output, Modifier.padding(12.dp),
                style = TextStyle(textDirection = TextDirection.Rtl, textAlign = TextAlign.Start),
            )
        }
        Button(
            onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("fixed", output))
            },
            enabled = output.isNotEmpty(),
        ) { Text("Copy") }
    }
}
