package com.campusmarket.server.seed

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.sin

/**
 * Illustrated demo avatars: a simple motif on a Golden Hour gradient. Deliberately not photographs of people.
 * Drawn with shapes only (no fonts), so they render the same in a headless container.
 */
object AvatarImages {
    const val SIZE = 480

    enum class Motif { SUNRISE, PEAKS, WAVES, RINGS, LEAF, CIRCUIT, ARCHES, PIXELS }

    private val amber = Color(0xFFC35A)
    private val pink = Color(0xFF6B81)
    private val berry = Color(0xD6305C)
    private val deepBerry = Color(0xB02A78)
    private val coral = Color(0xF0603A)
    private val plum = Color(0x2E1826)
    private val warmWhite = Color(0xFFF8F3)

    fun render(motif: Motif): SeedImage {
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            when (motif) {
                Motif.SUNRISE -> sunrise(g)
                Motif.PEAKS -> peaks(g)
                Motif.WAVES -> waves(g)
                Motif.RINGS -> rings(g)
                Motif.LEAF -> leaf(g)
                Motif.CIRCUIT -> circuit(g)
                Motif.ARCHES -> arches(g)
                Motif.PIXELS -> pixels(g)
            }
        } finally {
            g.dispose()
        }
        return SeedImage.encode(img, quality = 0.88f)
    }

    private fun Graphics2D.background(from: Color, to: Color, diagonal: Boolean = true) {
        val s = SIZE.toFloat()
        paint = if (diagonal) GradientPaint(0f, 0f, from, s, s, to) else GradientPaint(0f, 0f, from, 0f, s, to)
        fillRect(0, 0, SIZE, SIZE)
    }

    private fun alpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    private fun sunrise(g: Graphics2D) {
        g.background(amber, berry, diagonal = false)
        g.color = alpha(warmWhite, 235)
        g.fill(Ellipse2D.Double(140.0, 120.0, 200.0, 200.0))
        g.color = deepBerry
        g.fill(hill(0.0, 330.0, 300.0, 60.0))
        g.color = plum
        g.fill(hill(-40.0, 380.0, 360.0, 50.0))
    }

    private fun hill(x: Double, y: Double, w: Double, lift: Double) = Path2D.Double().apply {
        moveTo(x - w, SIZE.toDouble())
        lineTo(x - w, y + lift)
        curveTo(x - w / 2, y - lift, x + w / 2, y - lift, x + w * 1.4, y + lift)
        lineTo(SIZE + w, y + lift)
        lineTo(SIZE + w, SIZE.toDouble())
        closePath()
    }

    private fun peaks(g: Graphics2D) {
        g.background(Color(0xFFD9A0), coral, diagonal = false)
        g.color = alpha(warmWhite, 220)
        g.fill(Ellipse2D.Double(300.0, 80.0, 90.0, 90.0))
        fun peak(cx: Double, top: Double, half: Double, body: Color) {
            g.color = body
            g.fill(Path2D.Double().apply { moveTo(cx - half, 480.0); lineTo(cx, top); lineTo(cx + half, 480.0); closePath() })
            val cap = (480 - top) * 0.22
            val capHalf = half * cap / (480 - top)
            g.color = warmWhite
            g.fill(Path2D.Double().apply {
                moveTo(cx - capHalf, top + cap); lineTo(cx, top); lineTo(cx + capHalf, top + cap)
                lineTo(cx + capHalf * 0.35, top + cap * 0.8); lineTo(cx, top + cap); lineTo(cx - capHalf * 0.4, top + cap * 0.78)
                closePath()
            })
        }
        peak(330.0, 190.0, 220.0, deepBerry)
        peak(150.0, 140.0, 240.0, plum)
    }

    private fun waves(g: Graphics2D) {
        g.background(pink, amber)
        val bands = listOf(alpha(warmWhite, 200), alpha(coral, 230), alpha(berry, 235), alpha(deepBerry, 245))
        bands.forEachIndexed { i, c ->
            val base = 190.0 + i * 70
            g.color = c
            g.fill(Path2D.Double().apply {
                moveTo(0.0, SIZE.toDouble())
                for (x in 0..SIZE step 8) lineTo(x.toDouble(), base + 22 * sin(x / 480.0 * 2 * PI * 1.5 + i * 0.9))
                lineTo(SIZE.toDouble(), SIZE.toDouble())
                closePath()
            })
        }
    }

    private fun rings(g: Graphics2D) {
        g.background(deepBerry, coral)
        val colors = listOf(amber, warmWhite, pink, amber, warmWhite)
        g.stroke = BasicStroke(34f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        colors.forEachIndexed { i, c ->
            val r = 90.0 + i * 62
            g.color = alpha(c, 230 - i * 25)
            g.draw(Arc2D.Double(-r + 60, SIZE - r - 60, 2 * r, 2 * r, -10.0, 110.0, Arc2D.OPEN))
        }
    }

    private fun leaf(g: Graphics2D) {
        g.background(amber, Color(0xF28C5B))
        g.rotate(-PI / 5, 240.0, 240.0)
        val leaf = Path2D.Double().apply {
            moveTo(240.0, 60.0)
            curveTo(390.0, 150.0, 390.0, 330.0, 240.0, 420.0)
            curveTo(90.0, 330.0, 90.0, 150.0, 240.0, 60.0)
            closePath()
        }
        g.color = deepBerry
        g.fill(leaf)
        g.color = alpha(warmWhite, 210)
        g.stroke = BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawLine(240, 95, 240, 455)
        for (k in 0 until 4) {
            val y = 150 + k * 60
            g.drawLine(240, y + 30, 175 + k * 6, y - 10)
            g.drawLine(240, y + 30, 305 - k * 6, y - 10)
        }
    }

    private fun circuit(g: Graphics2D) {
        g.background(plum, deepBerry)
        val step = 80
        g.color = alpha(warmWhite, 70)
        for (x in 1..5) for (y in 1..5) g.fill(Ellipse2D.Double(x * step - 6.0, y * step - 6.0, 12.0, 12.0))
        val path = listOf(1 to 4, 2 to 4, 2 to 2, 4 to 2, 4 to 3, 5 to 3)
        g.color = amber
        g.stroke = BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        path.zipWithNext { (x1, y1), (x2, y2) -> g.drawLine(x1 * step, y1 * step, x2 * step, y2 * step) }
        g.color = pink
        g.drawLine(3 * step, 5 * step, 3 * step, 3 * step)
        g.drawLine(3 * step, 3 * step, 2 * step, 3 * step)
        for ((x, y) in listOf(1 to 4, 5 to 3, 3 to 5)) {
            g.color = warmWhite
            g.fill(Ellipse2D.Double(x * step - 22.0, y * step - 22.0, 44.0, 44.0))
            g.color = coral
            g.fill(Ellipse2D.Double(x * step - 11.0, y * step - 11.0, 22.0, 22.0))
        }
    }

    private fun arches(g: Graphics2D) {
        g.background(warmWhite, Color(0xFFD3C2))
        val colors = listOf(deepBerry, coral, amber)
        colors.forEachIndexed { i, c ->
            val w = 300.0 - i * 90
            val x = (SIZE - w) / 2
            val top = 110.0 + i * 70
            g.color = c
            g.fill(Path2D.Double().apply {
                moveTo(x, SIZE.toDouble())
                lineTo(x, top + w / 2)
                append(Arc2D.Double(x, top, w, w, 180.0, -180.0, Arc2D.OPEN), true)
                lineTo(x + w, SIZE.toDouble())
                closePath()
            })
        }
    }

    private fun pixels(g: Graphics2D) {
        g.background(Color(0xFFE7D6), Color(0xFFC9B0))
        // a 5x5 identicon-style pattern, mirrored left to right
        val bits = listOf("01110", "11011", "10101", "11111", "01010")
        val cell = 64.0
        val origin = (SIZE - 5 * cell) / 2
        for ((r, row) in bits.withIndex()) for ((c, bit) in row.withIndex()) {
            if (bit != '1') continue
            g.color = if ((r + c) % 3 == 0) coral else berry
            g.fill(RoundRectangle2D.Double(origin + c * cell + 3, origin + r * cell + 3, cell - 6, cell - 6, 14.0, 14.0))
        }
    }
}
