package com.toolbox.multitool.text

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import com.toolbox.multitool.ui.*
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
import com.toolbox.multitool.ui.*

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
        SectionCard("Your text") {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("Paste or type text") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Paste", { input = readClipboard(ctx) }, Modifier.weight(1f), Icons.Filled.ContentPaste)
                SecondaryButton("Clear", { input = "" }, Modifier.weight(1f), Icons.Filled.Close, enabled = input.isNotEmpty())
            }
            if (input.isNotEmpty()) ChipRow { Pill("${input.length} chars"); Pill("${input.lines().size} lines") }
        }
        SectionCard("Options") {
            SwitchRow("Fix Arabic ي ك → ی ک", normalize) { normalize = it }
            SwitchRow("Latin digits → Persian digits", digits) { digits = it }
        }
        SectionCard("Preview in a chat") {
            if (output.isEmpty()) Text("Your fixed text appears here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    SelectionContainer {
                        Text(
                            output, Modifier.padding(14.dp),
                            style = TextStyle(textDirection = TextDirection.Rtl, textAlign = TextAlign.Start, fontSize = 16.sp),
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Copy", { copyText(ctx, output) }, Modifier.weight(1f), Icons.Filled.ContentCopy, enabled = output.isNotEmpty())
            TonalButton("Share", { shareText(ctx, output) }, Modifier.weight(1f), Icons.Filled.Share, enabled = output.isNotEmpty())
        }
    }
}
