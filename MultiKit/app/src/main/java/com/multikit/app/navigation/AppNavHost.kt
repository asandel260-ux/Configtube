package com.multikit.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.multikit.app.MainActivity
import com.multikit.app.feature.crypto.CryptoScreen
import com.multikit.app.feature.home.HomeScreen
import com.multikit.app.feature.image.ImageConvertScreen
import com.multikit.app.feature.image.ImageResizeScreen
import com.multikit.app.feature.media.AudioEditorScreen
import com.multikit.app.feature.media.CircleVideoScreen
import com.multikit.app.feature.media.MusicMetaScreen
import com.multikit.app.feature.media.StickerLabScreen
import com.multikit.app.feature.media.VideoEditorScreen
import com.multikit.app.feature.network.NetworkScreen
import com.multikit.app.feature.notes.NotesScreen
import com.multikit.app.feature.pdf.PdfToolsScreen
import com.multikit.app.feature.qr.QrScreen
import com.multikit.app.feature.system.CleanerScreen
import com.multikit.app.feature.system.ScreenshotScreen
import com.multikit.app.feature.text.RtlTextScreen
import com.multikit.app.feature.viewer.FileViewerScreen

object Routes {
    const val HOME = "home"
    const val VIDEO_EDITOR = "video_editor"
    const val AUDIO_EDITOR = "audio_editor"
    const val MUSIC_META = "music_meta"
    const val CIRCLE_VIDEO = "circle_video"
    const val STICKERS = "stickers"
    const val IMAGE_CONVERT = "image_convert"
    const val IMAGE_RESIZE = "image_resize"
    const val PDF_TOOLS = "pdf_tools"
    const val RTL_TEXT = "rtl_text"
    const val QR = "qr"
    const val NETWORK = "network"
    const val CRYPTO = "crypto"
    const val NOTES = "notes"
    const val SCREENSHOT = "screenshot"
    const val CLEANER = "cleaner"
    const val VIEWER = "viewer?uri={uri}&text={text}"

    fun viewer(uri: String? = null, text: String? = null): String =
        "viewer?uri=${Uri.encode(uri ?: "")}&text=${Uri.encode(text ?: "")}"
}

@Composable
fun AppNavHost(incoming: MainActivity.Incoming?) {
    val nav = rememberNavController()

    // Deep-link from "Open with…" / share sheet.
    LaunchedEffect(incoming) {
        if (incoming != null && (incoming.uri != null || incoming.text != null)) {
            nav.navigate(Routes.viewer(incoming.uri, incoming.text)) {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onOpen = { route -> nav.navigate(route) })
        }
        composable(Routes.VIDEO_EDITOR) {
            VideoEditorScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.AUDIO_EDITOR) {
            AudioEditorScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.MUSIC_META) {
            MusicMetaScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CIRCLE_VIDEO) {
            CircleVideoScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.STICKERS) {
            StickerLabScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.IMAGE_CONVERT) {
            ImageConvertScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.IMAGE_RESIZE) {
            ImageResizeScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.PDF_TOOLS) {
            PdfToolsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.RTL_TEXT) {
            RtlTextScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.QR) {
            QrScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.NETWORK) {
            NetworkScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CRYPTO) {
            CryptoScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.NOTES) {
            NotesScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SCREENSHOT) {
            ScreenshotScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CLEANER) {
            CleanerScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.VIEWER,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                navArgument("text") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val uri = entry.arguments?.getString("uri").orEmpty()
            val text = entry.arguments?.getString("text").orEmpty()
            FileViewerScreen(
                uriString = uri.ifBlank { null },
                sharedText = text.ifBlank { null },
                onBack = { nav.popBackStack() },
            )
        }
    }
}