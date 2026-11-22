package app.cupnlog.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cupnlog.data.ShareCodec
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The reusable "big QR + share as text + copy link + bare code" block, shared
 * by the phase-3 setup share and the phase-4 ratings / merged-results screens.
 * Falls back to text/link when the encoded URL exceeds the ~2 KB QR guard
 * ([ShareCodec.QR_SAFE_URL_CHARS]).
 */
@Composable
fun ShareQrBlock(
    encode: ShareCodec.ShareEncode,
    title: String,
    subtitle: String? = null,
    shareLabel: String = "Share as text",
) {
    val context = LocalContext.current
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(modifier = Modifier.height(12.dp))

    if (encode.fitsQr) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val qr = remember(encode.url) { qrBitmap(encode.url) }
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "QR code",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(16.dp),
            )
        }
    } else {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "This is too large for a QR code. Share it as text or copy the link instead.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }
    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, encode.url)
            }
            context.startActivity(Intent.createChooser(send, shareLabel))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(shareLabel) }
    Spacer(modifier = Modifier.height(8.dp))

    var copiedLink by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            copyToClipboard(context, encode.url)
            copiedLink = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (copiedLink) "Link copied" else "Copy link") }
    Spacer(modifier = Modifier.height(8.dp))

    var copiedCode by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            copyToClipboard(context, encode.code)
            copiedCode = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (copiedCode) "Code copied" else "Copy plain code") }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Or copy the plain code",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    SelectionContainer {
        Text(
            encode.code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Renders the QR with a built-in quiet zone, black on white. */
internal fun qrBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 4),
    )
    val pixels = IntArray(size * size)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix.get(x, y)) black else white
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CofCup", text))
}