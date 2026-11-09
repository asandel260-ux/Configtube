package com.toolbox.multitool.crypto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.LogBox
import com.toolbox.multitool.ui.ToolScaffold

@Composable
fun CryptoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(Kind.Encoding) }
    var algoName by rememberSaveable { mutableStateOf(CryptoEngine.algos.first().name) }
    var input by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val algos = CryptoEngine.algos.filter { it.kind == kind }
    val algo = algos.firstOrNull { it.name == algoName } ?: algos.first()

    ToolScaffold("Encrypt & encode", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k ->
                FilterChip(kind == k, { kind = k; algoName = CryptoEngine.algos.first { it.kind == k }.name; output = "" }, { Text(k.name) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            algos.forEach { a -> FilterChip(algo.name == a.name, { algoName = a.name }, { Text(a.name) }) }
        }
        OutlinedTextField(input, { input = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        if (algo.needsKey) {
            OutlinedTextField(
                key, { key = it }, label = { Text(if (algo.kind == Kind.Cipher) "Password / key" else "Secret key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                error = null
                runCatching { CryptoEngine.encode(algo, input, key) }.onSuccess { output = it }.onFailure { error = it.message ?: it.toString() }
            }) { Text(if (algo.kind == Kind.Hash) "Compute" else "Encode / Encrypt") }
            if (algo.kind != Kind.Hash) {
                OutlinedButton(onClick = {
                    error = null
                    runCatching { CryptoEngine.decode(algo, input, key) }.onSuccess { output = it }
                        .onFailure { error = if (it is javax.crypto.AEADBadTagException) "Wrong password or corrupted data" else it.message ?: it.toString() }
                }) { Text("Decode / Decrypt") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LogBox(output)
        if (output.isNotEmpty()) TextButton(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("result", output))
        }) { Text("Copy result") }
        Text(
            "Ciphers use PBKDF2-HMAC-SHA256 (210k iterations) with a random salt and IV; output is Base64(salt|iv|ciphertext). " +
                "Caesar/Vigenère/ROT13 are classical toys, not security.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
