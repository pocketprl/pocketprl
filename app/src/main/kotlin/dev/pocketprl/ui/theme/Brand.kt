package dev.pocketprl.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app mark: the Pearl Research "P" (pearlresearch.ai/brand) peeking out of a
 * pocket. Same geometry as the launcher icon, cropped to the artwork, in one
 * colour so it can be tinted. Keep the paths verbatim.
 */
object Brand {
    private const val P = "M713.45,765.92h-159.5v-24.97h54.09v-321.78h81.83c84.61,0,133.15,52.71,133.15,115.12s-48.54,104.02-122.05,109.57l-19.42-15.26c63.8-11.1,85.99-41.61,85.99-94.32s-27.74-90.15-84.61-90.15h-20.8v296.82h51.32v24.97Z"
    private const val SHELL = "M558.11,419.17v228.85h-5.55c-85.99,0-134.54-51.32-134.54-113.73s49.93-115.12,134.54-115.12h5.55Z"
    private const val POCKET = "M27,50 H81 V69 A13,13 0 0 1 68,82 H40 A13,13 0 0 1 27,69 Z"
    private const val SEAM = "M30,53 H78 V68 A10,10 0 0 1 68,79 H40 A10,10 0 0 1 30,68 Z"
    private const val S = 0.11783f
    private const val TX = -12.658f
    private const val TY = -24.245f
    /** Artwork spans x 27..81, y 24..82 of the 108-unit launcher grid; shifted into a 64x68 box with four units of margin. */
    private const val OX = -23f
    private const val OY = -20f

    private fun nodes(d: String) = PathParser().parsePathString(d).toNodes()

    /** Filled silhouette: mark plus pocket. */
    val Mark: ImageVector by lazy {
        ImageVector.Builder(name = "pearl_mark", defaultWidth = 64.dp, defaultHeight = 68.dp, viewportWidth = 64f, viewportHeight = 68f)
            .addGroup(translationX = OX, translationY = OY)
            .addGroup(scaleX = S, scaleY = S, translationX = TX, translationY = TY)
            .addPath(pathData = nodes(P), fill = SolidColor(Color.Black))
            .addPath(pathData = nodes(SHELL), fill = SolidColor(Color.Black))
            .clearGroup()
            .addPath(pathData = nodes(POCKET), fill = SolidColor(Color.Black))
            .clearGroup()
            .build()
    }

    /** The pocket's seam, drawn over [Mark] in the background colour. */
    val Seam: ImageVector by lazy {
        ImageVector.Builder(name = "pearl_seam", defaultWidth = 64.dp, defaultHeight = 68.dp, viewportWidth = 64f, viewportHeight = 68f)
            .addGroup(translationX = OX, translationY = OY)
            .addPath(pathData = nodes(SEAM), stroke = SolidColor(Color.Black), strokeLineWidth = 1.3f)
            .clearGroup()
            .build()
    }
}

/** The mark in one colour with its seam in the colour behind it. */
@Composable
fun PearlMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    behind: Color = MaterialTheme.colorScheme.background,
) {
    Box(modifier = modifier.size(size)) {
        Icon(Brand.Mark, contentDescription = null, tint = color, modifier = Modifier.fillMaxSize())
        Icon(Brand.Seam, contentDescription = null, tint = behind.copy(alpha = 0.55f), modifier = Modifier.fillMaxSize())
    }
}
