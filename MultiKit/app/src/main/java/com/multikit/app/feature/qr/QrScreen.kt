package com.multikit.app.feature.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.LabeledField
import com.multikit.app.ui.components.OutputBlock
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumMap
import java.util.concurrent.Executors

@Composable
fun QrScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    ToolScaffold(title = "QR Codes", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Scan") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Generate") })
        }
        when (tab) {
            0 -> QrScanner()
            else -> QrGenerator()
        }
    }
}

@Composable
private fun QrScanner() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var scanned by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        SectionCard("Camera permission needed") {
            Text("Allow camera access to scan QR codes.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant permission")
            }
        }
        return
    }

    SectionCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            CameraPreview(
                onCode = { value ->
                    if (scanned == null) scanned = value
                },
                onError = { error = it },
                paused = scanned != null,
            )
            if (scanned != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Scanned ✓",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        ErrorBanner(error)
        if (scanned != null) {
            OutlinedButton(onClick = { scanned = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Scan another")
            }
        }
    }

    scanned?.let { value ->
        SectionCard("Result") {
            OutputBlock(value)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { Files.copyToClipboard(context, "QR", value) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                }
                IconButton(onClick = { Files.shareText(context, value) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share")
                }
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                        }.onFailure { error = "No app can open this link" }
                    }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "Open link")
                    }
                }
            }
        }
    }
}

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraPreview(
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
    paused: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember { BarcodeScanning.getClient() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            detector.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { proxy: ImageProxy ->
                        try {
                            val mediaImage = proxy.image
                            if (mediaImage != null) {
                                val input = InputImage.fromMediaImage(
                                    mediaImage,
                                    proxy.imageInfo.rotationDegrees,
                                )
                                detector.process(input)
                                    .addOnSuccessListener { barcodes ->
                                        barcodes.firstNotNullOfOrNull { it.rawValue }
                                            ?.let(onCode)
                                    }
                                    .addOnFailureListener { /* frame skipped */ }
                                    .addOnCompleteListener { proxy.close() }
                            } else {
                                proxy.close()
                            }
                        } catch (t: Throwable) {
                            proxy.close()
                        }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (t: Throwable) {
                    onError("Camera failed to start: ${t.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )

    // Pause/resume scanning by rebinding analysis when a result is present.
    LaunchedEffect(paused) {
        // Analyzer keeps running but results are ignored by the caller while paused;
        // no rebind needed, which keeps the camera stream stable.
    }
}

@Composable
private fun QrGenerator() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(512f) }
    var ecLevel by remember { mutableStateOf("M") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }

    SectionCard("Content") {
        LabeledField(
            label = "Text, URL, Wi-Fi config…",
            value = text,
            onValueChange = { text = it },
            singleLine = false,
            minLines = 3,
        )
        Text("Size: ${size.toInt()} px", style = MaterialTheme.typography.labelLarge)
        Slider(value = size, onValueChange = { size = it }, valueRange = 256f..1024f)
        Text("Error correction", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("L", "M", "Q", "H").forEach { level ->
                FilterChip(
                    selected = ecLevel == level,
                    onClick = { ecLevel = level },
                    label = { Text(level) },
                )
            }
        }
        Button(
            onClick = {
                error = null
                saved = null
                if (text.isBlank()) {
                    error = "Enter some content first"
                } else {
                    scope.launch {
                        try {
                            val bmp = withContext(Dispatchers.Default) {
                                generateQr(text, size.toInt(), ecLevel)
                            }
                            bitmap = bmp
                        } catch (t: Throwable) {
                            error = "Generation failed: ${t.message}"
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate QR code")
        }
        ErrorBanner(error)
    }

    bitmap?.let { bmp ->
        SectionCard {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Generated QR code",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val file = Files.tempFile(context, "qr_", "png")
                                file.outputStream().use { out ->
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                val uri = Files.exportToDownloads(context, file, "image/png")
                                withContext(Dispatchers.Main) {
                                    saved = if (uri != null) "Saved to Downloads" else "Save failed"
                                }
                            } catch (t: Throwable) {
                                withContext(Dispatchers.Main) { error = t.message }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Text("  Save")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val file = Files.tempFile(context, "qr_", "png")
                            file.outputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            withContext(Dispatchers.Main) { Files.share(context, file) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("  Share")
                }
            }
            saved?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun generateQr(content: String, size: Int, ec: String): Bitmap {
    val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.valueOf(ec))
        put(EncodeHintType.MARGIN, 2)
        put(EncodeHintType.CHARACTER_SET, "UTF-8")
    }
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bmp
}