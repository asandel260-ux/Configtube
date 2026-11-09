package com.toolbox.multitool

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.toolbox.multitool.cleaner.CleanerScreen
import com.toolbox.multitool.crypto.CryptoScreen
import com.toolbox.multitool.image.ImageConvertScreen
import com.toolbox.multitool.image.ImageResizeScreen
import com.toolbox.multitool.image.StickerScreen
import com.toolbox.multitool.media.AudioEditorScreen
import com.toolbox.multitool.media.AudioMetaScreen
import com.toolbox.multitool.media.VideoEditorScreen
import com.toolbox.multitool.network.NetworkScreen
import com.toolbox.multitool.notes.MindMapScreen
import com.toolbox.multitool.notes.NotesScreen
import com.toolbox.multitool.pdf.ImagesToPdfScreen
import com.toolbox.multitool.pdf.PdfExtractScreen
import com.toolbox.multitool.qr.QrScreen
import com.toolbox.multitool.screenshot.ScreenshotScreen
import com.toolbox.multitool.text.RtlScreen
import com.toolbox.multitool.viewer.OpenFileScreen

enum class Category(val label: String) {
    Media("Media"), Image("Images & stickers"), Documents("Documents & text"),
    Security("Security & network"), Notes("Notes & mind maps"), System("System"),
}

class Tool(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: Category,
    val icon: ImageVector,
    val content: @Composable (onBack: () -> Unit) -> Unit,
)

/** Adding a feature = adding one entry here. Nothing else needs to change. */
val ToolRegistry: List<Tool> = listOf(
    Tool("video", "Video editor", "Trim, rotate, resize, mute, compress", Category.Media, Icons.Filled.Videocam) { VideoEditorScreen(it) },
    Tool("audio", "Audio editor", "Trim, convert, extract from video", Category.Media, Icons.Filled.Audiotrack) { AudioEditorScreen(it) },
    Tool("meta", "Music tags", "Title, artist, album, length", Category.Media, Icons.Filled.LibraryMusic) { AudioMetaScreen(it) },

    Tool("imgconv", "Convert image", "PNG, JPEG, WebP", Category.Image, Icons.Filled.Image) { ImageConvertScreen(it) },
    Tool("imgsize", "Resize image", "Exact size or percentage", Category.Image, Icons.Filled.PhotoSizeSelectLarge) { ImageResizeScreen(it) },
    Tool("sticker", "Telegram sticker", "Image / video to sticker format", Category.Image, Icons.Filled.EmojiEmotions) { StickerScreen(it) },

    Tool("rtl", "Persian/English fixer", "Right-align mixed text, any length", Category.Documents, Icons.Filled.FormatAlignRight) { RtlScreen(it) },
    Tool("pdfimg", "PDF → images", "Extract every embedded image", Category.Documents, Icons.Filled.PictureAsPdf) { PdfExtractScreen(it) },
    Tool("img2pdf", "Images → PDF", "Merge pictures into one PDF", Category.Documents, Icons.Filled.PhotoLibrary) { ImagesToPdfScreen(it) },
    Tool("open", "File viewer", "PDF, text, ZIP / RAR / 7z", Category.Documents, Icons.Filled.FolderZip) { OpenFileScreen(it) },

    Tool("qr", "QR scanner", "Scan and generate", Category.Security, Icons.Filled.QrCodeScanner) { QrScreen(it) },
    Tool("net", "Network toolkit", "Ping, trace, ports, DNS, WHOIS, TLS", Category.Security, Icons.Filled.NetworkCheck) { NetworkScreen(it) },
    Tool("crypto", "Encrypt & encode", "AES, ChaCha20, hashes, Base64…", Category.Security, Icons.Filled.Lock) { CryptoScreen(it) },

    Tool("notes", "Notes", "Fast plain-text notes", Category.Notes, Icons.Filled.EditNote) { NotesScreen(it) },
    Tool("mind", "Mind maps", "Unlimited depth, zoom and focus", Category.Notes, Icons.Filled.AccountTree) { MindMapScreen(it) },

    Tool("shot", "Screenshot", "Quick Settings tile", Category.System, Icons.Filled.Screenshot) { ScreenshotScreen(it) },
    Tool("clean", "Cleaner", "App cache sizes and junk files", Category.System, Icons.Filled.CleaningServices) { CleanerScreen(it) },
)
