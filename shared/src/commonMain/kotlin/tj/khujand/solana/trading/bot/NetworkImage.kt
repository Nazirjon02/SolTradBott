package tj.khujand.solana.trading.bot

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Декодирует байты (PNG/JPEG/WEBP) в [ImageBitmap]; null — формат не распознан. Платформенно. */
expect fun decodeImageBitmapOrNull(bytes: ByteArray): ImageBitmap?

private val imageClient by lazy {
    HttpClient { install(HttpTimeout) { requestTimeoutMillis = 10_000 } }
}

// Кэш по URL: и удачную загрузку, и промах (null) помним, чтобы не дёргать сеть повторно.
private val imageCache = mutableMapOf<String, ImageBitmap?>()
private val imageCacheLock = Mutex()

/** Тянет и декодирует картинку по URL с in-memory кэшем. null — не вышло (нет сети / 404 / формат). */
suspend fun loadRemoteImage(url: String): ImageBitmap? {
    imageCacheLock.withLock { if (imageCache.containsKey(url)) return imageCache[url] }
    val bitmap = try {
        decodeImageBitmapOrNull(imageClient.get(url).body<ByteArray>())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    imageCacheLock.withLock { imageCache[url] = bitmap }
    return bitmap
}

/**
 * Круглая иконка монеты: грузит реальное лого по [url]; пока грузится или если не вышло —
 * показывает [fallback] (обычно эмодзи-заглушку). Форму/размер задаёт [modifier] снаружи.
 */
@Composable
fun CoinIcon(url: String?, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isNullOrBlank()) null else loadRemoteImage(url)
    }
    val bmp = bitmap
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            fallback()
        }
    }
}
