package tj.khujand.solana.trading.bot

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** Desktop (Skia): декод закодированных байтов в ImageBitmap. */
actual fun decodeImageBitmapOrNull(bytes: ByteArray): ImageBitmap? =
    try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
