package tj.khujand.solana.trading.bot

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Android (BitmapFactory): декод закодированных байтов в ImageBitmap. */
actual fun decodeImageBitmapOrNull(bytes: ByteArray): ImageBitmap? =
    try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
