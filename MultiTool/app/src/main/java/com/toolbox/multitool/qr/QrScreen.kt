@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.toolbox.multitool.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private enum class QrType(val label: String, val icon: ImageVector) {
    Url("Link", Icons.Filled.Link), Wifi("Wi-Fi network", Icons.Filled.Wifi), Phone("Phone number", Icons.Filled.Phone),
    Email("Email", Icons.Filled.Email), Plain("Text", Icons.Filled.TextFields),
}

private fun typeOf(s: String): QrType = when {
    s.startsWith("http://", true) || s.startsWith("https://", true) -> QrType.Url
    s.startsWith("WIFI:", true) -> QrType.Wifi
    s.startsWith("tel:", true) -> QrType.Phone
    s.startsWith("mailto:", true) -> QrType.Email
    else -> QrType.Plain
}

private fun wifiField(raw: String, key: String): String? =
    Regex("(?:^|;)$key:((?:\\\\.|[^;])*)").find(raw.removePrefix("WIFI:").removePrefix("wifi:"))
        ?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1")

@Composable
fun QrScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    ToolScaffold("QR", onBack, scroll = tab == 1 || result != null) {
        ChipRow {
            FilterChip(tab == 0, { tab = 0 }, { Text("Scan") }, leadingIcon = { Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)) })
            FilterChip(tab == 1, { tab = 1 }, { Text("Create") }, leadingIcon = { Icon(Icons.Filled.QrCode2, null, Modifier.size(18.dp)) })
        }
        if (tab == 0) ScanTab(granted, result, { result = it }, { permission.launch(Manifest.permission.CAMERA) }) else Generator()
    }
}

@Composable
private fun ColumnScope.ScanTab(granted: Boolean, result: String?, setResult: (String?) -> Unit, askPermission: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gallery = remember { BarcodeScanning.getClient() }
    var galleryMsg by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { gallery.close() } }

    val pickImage = rememberPicker(arrayOf("image/*")) { uris ->
        runCatching { gallery.process(InputImage.fromFilePath(ctx, uris.first())) }
            .onSuccess { t ->
                t.addOnSuccessListener { codes ->
                    val v = codes.firstOrNull()?.rawValue
                    if (v != null) { setResult(v); galleryMsg = "" } else galleryMsg = "No QR code found in that picture."
                }.addOnFailureListener { galleryMsg = "Could not read that picture." }
            }.onFailure { galleryMsg = "Could not open that picture." }
    }

    if (result == null) {
        if (granted) {
            Scanner(Modifier.fillMaxWidth().weight(1f)) { setResult(it) }
        } else {
            EmptyState(Icons.Filled.CameraAlt, "Camera access needed", "Allow the camera to scan, or import a picture that contains a QR code.")
            PrimaryButton("Allow camera", askPermission, icon = Icons.Filled.CameraAlt)
        }
        TonalButton("Scan from a picture", pickImage, icon = Icons.Filled.PhotoLibrary)
        if (galleryMsg.isNotEmpty()) Text(galleryMsg, color = MaterialTheme.colorScheme.error)
    } else {
        val type = typeOf(result)
        SectionCard(type.label) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(type.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                if (type == QrType.Wifi) {
                    Column {
                        Text(wifiField(result, "S").orEmpty().ifBlank { "Unknown network" }, style = MaterialTheme.typography.titleMedium)
                        Text("Security: ${wifiField(result, "T") ?: "none"}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(result, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            if (type == QrType.Wifi) wifiField(result, "P")?.takeIf { it.isNotEmpty() }?.let { pw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Password: $pw", Modifier.weight(1f))
                    IconButton(onClick = { copyText(ctx, pw) }) { Icon(Icons.Filled.ContentCopy, "Copy password") }
                }
            }
        }
        if (type == QrType.Url || type == QrType.Phone || type == QrType.Email) {
            PrimaryButton(
                when (type) { QrType.Url -> "Open link"; QrType.Phone -> "Call"; else -> "Write email" },
                onClick = {
                    val uri = Uri.parse(result)
                    runCatching { ctx.startActivity(Intent(if (type == QrType.Phone) Intent.ACTION_DIAL else Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                icon = Icons.Filled.OpenInNew,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton("Copy", { copyText(ctx, result) }, Modifier.weight(1f), Icons.Filled.ContentCopy)
            TonalButton("Share", { shareText(ctx, result) }, Modifier.weight(1f), Icons.Filled.Share)
        }
        SecondaryButton("Scan again", { setResult(null) }, icon = Icons.Filled.Refresh)
    }
}

@Composable
private fun Scanner(modifier: Modifier, onResult: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner: BarcodeScanner = remember { BarcodeScanning.getClient() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { provider?.unbindAll(); scanner.close(); executor.shutdown() } }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    Box(modifier.clip(MaterialTheme.shapes.large).background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            PreviewView(c).also { pv ->
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val p = future.get(); provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media == null || done) { proxy.close(); return@setAnalyzer }
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes -> codes.firstOrNull()?.rawValue?.let { if (!done) { done = true; onResult(it) } } }
                            .addOnCompleteListener { proxy.close() }
                    }
                    p.unbindAll()
                    camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(c))
            }
        })
        // viewfinder frame
        Box(Modifier.align(Alignment.Center).size(240.dp).border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp)))
        Text(
            "Point at a QR code", Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            color = Color.White, style = MaterialTheme.typography.labelLarge,
        )
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            FilledIconToggleButton(torch, { torch = it }, Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Torch")
            }
        }
    }
}

private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

@Composable
private fun Generator() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var sec by rememberSaveable { mutableStateOf("WPA") }
    var showPass by remember { mutableStateOf(false) }
    val saved = remember { mutableStateListOf<Saved>() }

    val content = when (kind) {
        1 -> if (text.isBlank()) "" else if (text.startsWith("http", true)) text else "https://$text"
        2 -> if (ssid.isBlank()) "" else "WIFI:T:${if (sec == "None") "nopass" else sec};S:${esc(ssid)};P:${esc(pass)};;"
        else -> text
    }
    val bmp = remember(content) { if (content.isBlank()) null else runCatching { qrBitmap(content, 768) }.getOrNull() }

    ChipRow {
        listOf("Text" to Icons.Filled.TextFields, "Link" to Icons.Filled.Link, "Wi-Fi" to Icons.Filled.Wifi).forEachIndexed { i, (label, icon) ->
            FilterChip(kind == i, { kind = i; saved.clear() }, { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
        }
    }
    SectionCard {
        if (kind == 2) {
            OutlinedTextField(ssid, { ssid = it }, label = { Text("Network name (SSID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton({ showPass = !showPass }) { Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show password") } },
            )
            ChipRow { listOf("WPA", "WEP", "None").forEach { s -> FilterChip(sec == s, { sec = s }, { Text(s) }) } }
        } else {
            OutlinedTextField(
                text, { text = it }, label = { Text(if (kind == 1) "Web address" else "Text") }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { Row { IconButton({ text = readClipboard(ctx) }) { Icon(Icons.Filled.ContentPaste, "Paste") }; if (text.isNotEmpty()) IconButton({ text = "" }) { Icon(Icons.Filled.Close, "Clear") } } },
            )
        }
    }
    if (bmp != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bmp.asImageBitmap(), "QR code", Modifier.size(280.dp).clip(MaterialTheme.shapes.large).background(Color.White).padding(12.dp), filterQuality = FilterQuality.None)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save PNG", onClick = {
                scope.launch {
                    val name = "qr_${System.currentTimeMillis() / 1000}.png"
                    val tmp = Files.tempFile(ctx, name)
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Files.saveToDownloads(ctx, tmp, name, "image/png")?.let { saved.add(Saved(name, it, "image/png")) }
                    tmp.delete()
                }
            }, Modifier.weight(1f), Icons.Filled.Save)
            TonalButton("Share", onClick = {
                scope.launch {
                    val name = "qr_${System.currentTimeMillis() / 1000}.png"
                    val tmp = Files.tempFile(ctx, name)
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Files.saveToDownloads(ctx, tmp, name, "image/png")?.let { shareFiles(ctx, listOf(Saved(name, it, "image/png"))) }
                    tmp.delete()
                }
            }, Modifier.weight(1f), Icons.Filled.Share)
        }
        ResultsCard(saved)
    } else {
        EmptyState(Icons.Filled.QrCode2, "Your QR code appears here", "Type something above and it updates live.")
    }
}

private fun qrBitmap(content: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
