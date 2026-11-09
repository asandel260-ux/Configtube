package com.toolbox.multitool.screenshot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.toolbox.multitool.R
import com.toolbox.multitool.ui.ToolScaffold
import java.util.concurrent.Executor

/**
 * Flow: Quick Settings tile -> translucent ScreenshotActivity (asks for the MediaProjection
 * consent) -> foreground CaptureService grabs ONE frame and saves it to Pictures/Screenshots.
 * Android 14+ makes the consent single-use, so each capture shows the system prompt.
 */
class ScreenshotTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.tile_label); updateTile() }
    }

    override fun onClick() {
        val intent = Intent(this, ScreenshotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}

class ScreenshotActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data),
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        consent.launch(mpm.createScreenCaptureIntent())
    }
}

class CaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("shot", "Screenshot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "shot").setSmallIcon(R.drawable.ic_screenshot).setContentTitle("Taking screenshot").build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection.registerCallback(object : MediaProjection.Callback() {}, handler) // required on Android 14+

        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = projection.createVirtualDisplay(
            "multitool-shot", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )

        fun finish(msg: String) {
            vd.release(); reader.close(); projection.stop()
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        var tries = 0
        val grab = object : Runnable {
            override fun run() {
                val img = reader.acquireLatestImage()
                if (img == null) {
                    if (++tries < 15) handler.postDelayed(this, 150) else finish("Screenshot failed")
                    return
                }
                val plane = img.planes[0]
                val pad = plane.rowStride - plane.pixelStride * w
                val full = Bitmap.createBitmap(w + pad / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                img.close()
                val shot = Bitmap.createBitmap(full, 0, 0, w, h)
                Thread {
                    val ok = save(shot)
                    handler.post { finish(if (ok) "Screenshot saved" else "Could not save screenshot") }
                }.start()
            }
        }
        handler.postDelayed(grab, 450) // let the Quick Settings shade finish collapsing
        return START_NOT_STICKY
    }

    private fun save(bmp: Bitmap): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}

@Composable
fun ScreenshotScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    ToolScaffold("Screenshot", onBack) {
        Text("Add the Screenshot tile to your Quick Settings panel (swipe down, then edit tiles). Tapping it collapses the panel and captures the screen to Pictures/Screenshots.")
        if (Build.VERSION.SDK_INT >= 33) {
            Button(onClick = {
                val sbm = ctx.getSystemService(StatusBarManager::class.java)
                sbm.requestAddTileService(
                    ComponentName(ctx, ScreenshotTileService::class.java), "Screenshot",
                    Icon.createWithResource(ctx, R.drawable.ic_screenshot), Executor { it.run() },
                ) { result -> msg = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) "Tile added" else "Tile not added" }
            }) { Text("Add tile to Quick Settings") }
        } else Text("On Android 12 and below, add it manually from the Quick Settings edit screen.")
        OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, ScreenshotActivity::class.java)) }) { Text("Take a screenshot now (test)") }
        if (msg.isNotEmpty()) Text(msg)
        Text("Android 14+ asks for screen-capture consent on every capture; that prompt is a system rule and cannot be skipped.", style = MaterialTheme.typography.bodySmall)
    }
}
