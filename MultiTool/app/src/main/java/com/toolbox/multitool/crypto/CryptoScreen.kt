package com.toolbox.multitool.crypto

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(Kind.Encoding) }
    var algoName by rememberSaveable { mutableStateOf(CryptoEngine.algos.first().name) }
    var decode by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var cipherOut by remember { mutableStateOf("") }
    var cipherErr by remember { mutableStateOf<String?>(null) }

    val algos = CryptoEngine.algos.filter { it.kind == kind }
    val algo = algos.firstOrNull { it.name == algoName } ?: algos.first()
    val live = algo.kind != Kind.Cipher
    val canDecode = algo.kind != Kind.Hash
    val liveResult: Result<String>? = remember(algo, decode, input, key) {
        if (live && input.isNotEmpty()) runCatching {
            if (decode && canDecode) CryptoEngine.decode(algo, input, key) else CryptoEngine.encode(algo, input, key)
        } else null
    }
    val output = if (live) liveResult?.getOrNull().orEmpty() else cipherOut
    val error = if (live) liveResult?.exceptionOrNull()?.let { it.message ?: "Invalid input" } else cipherErr

    ToolScaffold("Encrypt & encode", onBack) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Kind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { kind = k; algoName = CryptoEngine.algos.first { it.kind == k }.name; cipherOut = ""; cipherErr = null },
                    shape = SegmentedButtonDefaults.itemShape(i, Kind.entries.size),
                ) { Text(k.name) }
            }
        }

        SectionCard("Algorithm") {
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.large) {
                    Text(algo.name, Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    algos.forEach { a ->
                        DropdownMenuItem(text = { Text(a.name) }, onClick = { algoName = a.name; menu = false; cipherOut = ""; cipherErr = null })
                    }
                }
            }
            if (canDecode) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!decode, { decode = false; cipherOut = "" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(if (algo.kind == Kind.Cipher) "Encrypt" else "Encode") }
                    SegmentedButton(decode, { decode = true; cipherOut = "" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(if (algo.kind == Kind.Cipher) "Decrypt" else "Decode") }
                }
            }
        }

        SectionCard("Input") {
            OutlinedTextField(
                input, { input = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                trailingIcon = {
                    Row {
                        IconButton({ input = readClipboard(ctx) }) { Icon(Icons.Filled.ContentPaste, "Paste") }
                        if (input.isNotEmpty()) IconButton({ input = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
            )
            if (algo.needsKey) {
                OutlinedTextField(
                    key, { key = it }, label = { Text(if (algo.kind == Kind.Cipher) "Password / key" else "Secret key") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide") } },
                )
            }
        }

        if (!live) {
            PrimaryButton(if (decode) "Decrypt" else "Encrypt", icon = Icons.Filled.Lock, enabled = input.isNotEmpty(), onClick = {
                cipherErr = null
                runCatching { if (decode) CryptoEngine.decode(algo, input, key) else CryptoEngine.encode(algo, input, key) }
                    .onSuccess { cipherOut = it }
                    .onFailure { cipherOut = ""; cipherErr = if (it is javax.crypto.AEADBadTagException) "Wrong password or corrupted data" else it.message ?: it.toString() }
            })
        }
        if (error != null && input.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (output.isNotEmpty()) {
            SectionCard("Result") {
                LogBox(output)
                ChipRow { Pill("${output.length} chars") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("Copy", { copyText(ctx, output) }, Modifier.weight(1f), Icons.Filled.ContentCopy)
                    TonalButton("Share", { shareText(ctx, output) }, Modifier.weight(1f), Icons.Filled.Share)
                }
                SecondaryButton("Use result as input", { input = output; cipherOut = "" }, icon = Icons.Filled.SwapVert)
            }
        }
        Text(
            "Ciphers use PBKDF2-HMAC-SHA256 (210k iterations) with a random salt and IV; output is Base64(salt|iv|ciphertext). " +
                "Caesar, Vigenère and ROT13 are classical toys, not security.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
