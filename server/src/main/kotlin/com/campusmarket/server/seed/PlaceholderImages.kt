package com.campusmarket.server.seed

import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCategory.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Generates simple placeholder product photos: soft gradient, a category icon drawn from shapes, and the item title. */
object PlaceholderImages {
    const val SIZE = 800

    class Rendered(val bytes: ByteArray, val width: Int, val height: Int)

    fun render(title: String, category: ListingCategory, variant: Int): Rendered {
        System.setProperty("java.awt.headless", "true")
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val hue = (((category.ordinal * 0.11f) + variant * 0.035f) % 1f)
            val top = Color.getHSBColor(hue, 0.25f, 0.98f)
            val bottom = Color.getHSBColor((hue + 0.08f) % 1f, 0.45f, 0.86f)
            g.paint = GradientPaint(0f, 0f, top, SIZE.toFloat(), SIZE.toFloat(), bottom)
            g.fillRect(0, 0, SIZE, SIZE)
            // soft background blobs differ per variant
            g.color = Color(255, 255, 255, 50)
            g.fill(Ellipse2D.Double(-120.0 + variant * 90, 480.0 - variant * 60, 420.0, 420.0))
            g.fill(Ellipse2D.Double(480.0 - variant * 70, -140.0 + variant * 50, 380.0, 380.0))

            val ink = Color.getHSBColor(hue, 0.55f, 0.35f)
            val accent = Color.getHSBColor((hue + 0.5f) % 1f, 0.35f, 0.97f)
            // shadow + icon, centred around (400, 330)
            g.translate(400.0, 330.0)
            g.color = Color(0, 0, 0, 30)
            g.fill(Ellipse2D.Double(-170.0, 150.0, 340.0, 36.0))
            icon(g, category, ink, accent)
            g.translate(-400.0, -330.0)

            // title plate
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 46)
            val lines = wrap(g, title, SIZE - 160, 3)
            val lineH = g.fontMetrics.height
            val plateH = lines.size * lineH + 50
            val plateY = SIZE - plateH - 50
            g.color = Color(255, 255, 255, 215)
            g.fill(RoundRectangle2D.Double(50.0, plateY.toDouble(), (SIZE - 100).toDouble(), plateH.toDouble(), 36.0, 36.0))
            g.color = ink
            lines.forEachIndexed { i, s ->
                val w = g.fontMetrics.stringWidth(s)
                g.drawString(s, (SIZE - w) / 2, plateY + 25 + g.fontMetrics.ascent + i * lineH)
            }
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.82f
        }
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return Rendered(out.toByteArray(), SIZE, SIZE)
    }

    private fun wrap(g: Graphics2D, text: String, maxWidth: Int, maxLines: Int): List<String> {
        val fm = g.fontMetrics
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (fm.stringWidth(next) <= maxWidth || cur.isEmpty()) cur = next else { lines += cur; cur = word }
        }
        if (cur.isNotEmpty()) lines += cur
        if (lines.size > maxLines) {
            val kept = lines.take(maxLines).toMutableList()
            var last = kept.last() + "..."
            while (fm.stringWidth(last) > maxWidth && last.length > 4) last = last.dropLast(4) + "..."
            kept[maxLines - 1] = last
            return kept
        }
        return lines
    }

    private fun icon(g: Graphics2D, c: ListingCategory, ink: Color, accent: Color) {
        g.stroke = BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        fun fillStroke(s: java.awt.Shape, fill: Color) { g.color = fill; g.fill(s); g.color = ink; g.draw(s) }
        when (c) {
            TEXTBOOKS -> {
                fillStroke(RoundRectangle2D.Double(-150.0, 90.0, 300.0, 60.0, 14.0, 14.0), accent)
                fillStroke(RoundRectangle2D.Double(-130.0, 30.0, 270.0, 60.0, 14.0, 14.0), Color.WHITE)
                fillStroke(RoundRectangle2D.Double(-110.0, -30.0, 240.0, 60.0, 14.0, 14.0), accent)
                fillStroke(RoundRectangle2D.Double(-140.0, -90.0, 280.0, 60.0, 14.0, 14.0), Color.WHITE)
            }
            ELECTRONICS -> {
                fillStroke(RoundRectangle2D.Double(-170.0, -110.0, 340.0, 220.0, 24.0, 24.0), Color.WHITE)
                fillStroke(RoundRectangle2D.Double(-145.0, -85.0, 290.0, 170.0, 10.0, 10.0), accent)
                g.color = ink; g.drawLine(0, 110, 0, 150); g.drawLine(-70, 150, 70, 150)
            }
            FURNITURE -> {
                fillStroke(RoundRectangle2D.Double(-150.0, -20.0, 300.0, 70.0, 20.0, 20.0), accent)
                fillStroke(RoundRectangle2D.Double(-150.0, -110.0, 300.0, 100.0, 24.0, 24.0), Color.WHITE)
                g.color = ink; g.drawLine(-120, 50, -120, 140); g.drawLine(120, 50, 120, 140)
            }
            CLOTHING -> {
                val p = Path2D.Double().apply {
                    moveTo(-60.0, -120.0); lineTo(-170.0, -60.0); lineTo(-130.0, -10.0); lineTo(-90.0, -35.0)
                    lineTo(-90.0, 130.0); lineTo(90.0, 130.0); lineTo(90.0, -35.0); lineTo(130.0, -10.0)
                    lineTo(170.0, -60.0); lineTo(60.0, -120.0)
                    quadTo(0.0, -70.0, -60.0, -120.0); closePath()
                }
                fillStroke(p, Color.WHITE)
            }
            DORM -> {
                fillStroke(RoundRectangle2D.Double(-100.0, -70.0, 200.0, 190.0, 40.0, 40.0), Color.WHITE)
                g.color = ink; g.draw(Ellipse2D.Double(60.0, -30.0, 90.0, 90.0))
                fillStroke(RoundRectangle2D.Double(-100.0, -70.0, 200.0, 50.0, 24.0, 24.0), accent)
            }
            BIKES -> {
                g.color = ink
                g.draw(Ellipse2D.Double(-180.0, 10.0, 140.0, 140.0)); g.draw(Ellipse2D.Double(40.0, 10.0, 140.0, 140.0))
                val frame = Path2D.Double().apply { moveTo(-110.0, 80.0); lineTo(-20.0, -50.0); lineTo(110.0, 80.0); moveTo(-20.0, -50.0); lineTo(60.0, -50.0); lineTo(110.0, 80.0); moveTo(-110.0, 80.0); lineTo(20.0, 80.0); lineTo(-20.0, -50.0) }
                g.draw(frame); g.color = accent; g.fill(Ellipse2D.Double(-30.0, 70.0, 20.0, 20.0))
            }
            TICKETS -> {
                fillStroke(RoundRectangle2D.Double(-170.0, -90.0, 340.0, 180.0, 30.0, 30.0), accent)
                g.color = Color.WHITE; g.fill(Ellipse2D.Double(-190.0, -20.0, 40.0, 40.0)); g.fill(Ellipse2D.Double(150.0, -20.0, 40.0, 40.0))
                g.color = ink; g.stroke = BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(14f, 14f), 0f)
                g.drawLine(60, -80, 60, 80)
            }
            SPORTS -> {
                fillStroke(Ellipse2D.Double(-130.0, -130.0, 260.0, 260.0), accent)
                g.color = ink; g.drawLine(-130, 0, 130, 0); g.drawLine(0, -130, 0, 130)
                g.draw(java.awt.geom.Arc2D.Double(-230.0, -130.0, 200.0, 260.0, -60.0, 120.0, java.awt.geom.Arc2D.OPEN))
                g.draw(java.awt.geom.Arc2D.Double(30.0, -130.0, 200.0, 260.0, 120.0, 120.0, java.awt.geom.Arc2D.OPEN))
            }
            OTHER -> {
                val star = Path2D.Double()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 150.0 else 65.0
                    val a = Math.PI / 5 * i - Math.PI / 2
                    if (i == 0) star.moveTo(r * Math.cos(a), r * Math.sin(a)) else star.lineTo(r * Math.cos(a), r * Math.sin(a))
                }
                star.closePath()
                fillStroke(star, Color.WHITE)
            }
        }
    }
}
