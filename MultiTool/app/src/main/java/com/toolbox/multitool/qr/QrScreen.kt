@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.toolbox.multitool.qr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import com.toolbox.multitool.ui.ToolScaffold
import java.util.concurrent.Executors

@Composable
fun QrScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    ToolScaffold("QR", onBack, scroll = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, { Text("Scan") })
            FilterChip(tab == 1, { tab = 1 }, { Text("Generate") })
        }
        if (tab == 0) {
            if (result == null) {
                if (granted) Scanner(Modifier.fillMaxWidth().weight(1f)) { result = it }
                else Text("Camera permission is required to scan.")
            } else {
                Text(result!!, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("qr", result)) }) { Text("Copy") }
                    if (result!!.startsWith("http://") || result!!.startsWith("https://")) {
                        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open link") }
                    }
                    OutlinedButton(onClick = { result = null }) { Text("Scan again") }
                }
            }
        } else Generator()
    }
}

@Composable
private fun Scanner(modifier: Modifier, onResult: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner: BarcodeScanner = remember { BarcodeScanning.getClient() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var done by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll(); scanner.close(); executor.shutdown() }
    }

    AndroidView(modifier = modifier, factory = { c ->
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
                        .addOnSuccessListener { codes ->
                            codes.firstOrNull()?.rawValue?.let { if (!done) { done = true; onResult(it) } }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(c))
        }
    })
}

@Composable
private fun Generator() {
    var text by rememberSaveable { mutableStateOf("") }
    val bmp = remember(text) { if (text.isBlank()) null else runCatching { qrBitmap(text, 768) }.getOrNull() }
    OutlinedTextField(text, { text = it }, label = { Text("Text or URL") }, modifier = Modifier.fillMaxWidth())
    bmp?.let { Image(it.asImageBitmap(), "QR code", Modifier.size(280.dp), filterQuality = FilterQuality.None) }
}

private fun qrBitmap(content: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
