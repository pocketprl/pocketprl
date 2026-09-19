package dev.pocketprl.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import dev.pocketprl.R

/**
 * A centre mark six modules wide or under decodes reliably; at seven it can be
 * mistaken for a finder pattern. Nine modules of hole keeps the mark at two-thirds of the white.
 */
private const val MAX_HOLE_MODULES = 9
private const val MAX_MARK_MODULES = 6.0f

/** A QR code as modules, not pixels, so it can be drawn at any size in any colour. */
class QrMatrix(val n: Int, private val bits: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = bits[y * n + x]
}

/**
 * Encodes at the highest error-correction level so a quarter of the middle can be
 * covered by a mark and the code still scans. Null when zxing refuses the input.
 */
fun encodeQr(data: String): QrMatrix? = runCatching {
    val m = Encoder.encode(data, ErrorCorrectionLevel.H).matrix
    val n = m.width
    QrMatrix(n, BooleanArray(n * n) { i -> m.get(i % n, i / n).toInt() == 1 })
}.getOrNull()

/**
 * Draws [matrix] with rounded modules and rounded finder patterns, and leaves a
 * hole in the middle for [logo]. The quiet zone is the caller's padding.
 */
@Composable
fun QrCode(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
    foreground: Color,
    background: Color,
    logo: (@Composable () -> Unit)? = null,
    /** Side of the centre hole as a fraction of the code's side, capped at [MAX_HOLE_MODULES]. */
    logoFraction: Float = 0.22f,
    contentDescription: String? = null,
) {
    val n = matrix.n
    val description = contentDescription ?: stringResource(R.string.receive_qr_desc)
    // Capped in modules, not just as a fraction: the mark must stay under a finder pattern's seven modules.
    val hole = if (logo != null) (minOf((n * logoFraction).toInt(), MAX_HOLE_MODULES) or 1) else 0
    val markModules = minOf(hole * 0.67f, MAX_MARK_MODULES)
    Box(modifier = modifier.aspectRatio(1f).semantics { this.contentDescription = description }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cell = size.width / n
            val h0 = (n - hole) / 2
            val h1 = h0 + hole
            fun finder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
            val inset = cell * 0.08f
            val dot = Size(cell - inset * 2, cell - inset * 2)
            val r = CornerRadius(cell * 0.3f)
            for (y in 0 until n) for (x in 0 until n) {
                if (!matrix[x, y] || finder(x, y) || (x in h0 until h1 && y in h0 until h1)) continue
                drawRoundRect(foreground, topLeft = Offset(x * cell + inset, y * cell + inset), size = dot, cornerRadius = r)
            }
            // Finder patterns as three nested rounded squares.
            for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val o = Offset(fx * cell, fy * cell)
                drawRoundRect(foreground, o, Size(cell * 7, cell * 7), CornerRadius(cell * 1.9f))
                drawRoundRect(background, o + Offset(cell, cell), Size(cell * 5, cell * 5), CornerRadius(cell * 1.2f))
                drawRoundRect(foreground, o + Offset(cell * 2, cell * 2), Size(cell * 3, cell * 3), CornerRadius(cell * 0.8f))
            }
        }
        if (logo != null) {
            Box(modifier = Modifier.align(Alignment.Center).fillMaxSize(markModules / n), contentAlignment = Alignment.Center) { logo() }
        }
    }
}
