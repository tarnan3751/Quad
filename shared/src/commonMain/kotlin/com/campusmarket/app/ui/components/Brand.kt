package com.campusmarket.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusmarket.app.ui.theme.CampusTheme

/*
 * The Quad mark, "Sun on the Quad": the campus quad from above, four lawn panels split by crossing paths (the 8-unit
 * gaps) with the sun in one corner. Geometry is the approved 96-unit drawing (visual bounds 18..78 on both axes), the
 * same numbers scripts/generate_app_icon.py and the server-rendered pages use. All shapes are one solid colour.
 */
private const val MARK_MIN = 18f
private const val MARK_SPAN = 60f

/** The bare mark (no tile), fitted and centred in a square of [size]. White on the gradient, berry on plain surfaces. */
@Composable
fun QuadMark(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = Color.White) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / MARK_SPAN
        val dx = (this.size.width - MARK_SPAN * k) / 2f - MARK_MIN * k
        val dy = (this.size.height - MARK_SPAN * k) / 2f - MARK_MIN * k
        fun x(v: Float) = dx + v * k
        fun y(v: Float) = dy + v * k
        // Top-left lawn panel, outer corner rounded (r = 12).
        val topLeft = Path().apply {
            moveTo(x(18f), y(44f)); lineTo(x(18f), y(30f))
            arcTo(Rect(x(18f), y(18f), x(42f), y(42f)), 180f, 90f, false)
            lineTo(x(44f), y(18f)); lineTo(x(44f), y(44f)); close()
        }
        // Bottom-left panel.
        val bottomLeft = Path().apply {
            moveTo(x(18f), y(52f)); lineTo(x(44f), y(52f)); lineTo(x(44f), y(78f)); lineTo(x(30f), y(78f))
            arcTo(Rect(x(18f), y(54f), x(42f), y(78f)), 90f, 90f, false)
            close()
        }
        // Bottom-right panel.
        val bottomRight = Path().apply {
            moveTo(x(52f), y(52f)); lineTo(x(78f), y(52f)); lineTo(x(78f), y(66f))
            arcTo(Rect(x(54f), y(54f), x(78f), y(78f)), 0f, 90f, false)
            lineTo(x(52f), y(78f)); close()
        }
        drawPath(topLeft, color)
        drawCircle(color, radius = 13f * k, center = Offset(x(65f), y(31f)))
        drawPath(bottomLeft, color)
        drawPath(bottomRight, color)
    }
}

/** The app icon as a UI element: the white mark (≈ 61% of the width, like the launcher icon) on the brand gradient. */
@Composable
fun QuadAppIcon(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier
            .size(size)
            .background(CampusTheme.brandGradient, RoundedCornerShape(size * 0.23f))
            .semantics { contentDescription = "Quad" },
        contentAlignment = Alignment.Center,
    ) {
        QuadMark(size = size * 0.61f)
    }
}

/** The lowercase `quad` wordmark in Plus Jakarta Sans 800. */
@Composable
fun QuadWordmark(modifier: Modifier = Modifier, color: Color = Color.White, fontSize: TextUnit = 56.sp) {
    Text("quad", modifier = modifier, style = CampusTheme.wordmark, color = color, fontSize = fontSize, maxLines = 1)
}
