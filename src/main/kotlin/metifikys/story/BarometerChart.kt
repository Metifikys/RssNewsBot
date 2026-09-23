package metifikys.story

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * Draws a [Barometer.Result] as a 1200×675 PNG: one panel per series (small multiples, each with its
 * own y-scale), bars per ISO week, zero baseline, a few recessive gridlines, shared week labels, a
 * header naming each series with this week's number, and direct value labels. JDK only (java.awt, ImageIO)
 * and headless; the Cyrillic text uses the bundled DejaVu Sans (`/fonts`, see LICENSE-DejaVu.txt),
 * so the server's fontconfig does not matter. At most two series, colour-blind-safe blue / orange.
 */
object BarometerChart {

    init {
        // First, before the colour constants load AWT: the server has no display. Main sets it too;
        // this covers tests and other entry points. A no-op once the toolkit is up.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    }

    const val WIDTH = 1200
    const val HEIGHT = 675

    private val SURFACE = Color(0xFC, 0xFC, 0xFB)
    private val TEXT = Color(0x0B, 0x0B, 0x0B)
    private val TEXT_SECONDARY = Color(0x52, 0x51, 0x4E)
    private val GRID = Color(0xE4, 0xE3, 0xDF)
    private val BASELINE = Color(0x8A, 0x89, 0x84)
    /** Categorical slots 1 and 2 (validated: CVD ΔE ≥ 24, ≥ 3:1 on the surface). */
    private val SERIES = listOf(Color(0x2A, 0x78, 0xD6), Color(0xEB, 0x68, 0x34))

    private val WEEK_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

    private val regular: Font by lazy { loadFont("/fonts/DejaVuSans.ttf", Font.PLAIN) }
    private val bold: Font by lazy { loadFont("/fonts/DejaVuSans-Bold.ttf", Font.BOLD) }

    /**
     * @param title the chart heading, e.g. `Барометр індустрії · gaming`
     * @param subtitle one line under it, e.g. `12 тижнів до 21.09 · за нашими джерелами`
     */
    fun render(r: Barometer.Result, title: String, subtitle: String): ByteArray {
        val img = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.color = SURFACE
            g.fillRect(0, 0, WIDTH, HEIGHT)
            draw(g, r, title, subtitle)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }

    private fun draw(g: Graphics2D, r: Barometer.Result, title: String, subtitle: String) {
        val left = 96
        val right = WIDTH - 48

        // Heading and subtitle.
        g.font = bold.deriveFont(38f)
        g.color = TEXT
        g.drawString(title, 48, 64)
        g.font = regular.deriveFont(24f)
        g.color = TEXT_SECONDARY
        g.drawString(subtitle, 48, 102)

        // Small multiples: one panel per series, each with its own y-scale. Layoffs (a handful a
        // week) and release news (dozens) on one axis would flatten the smaller series.
        val panels = max(1, r.series.size)
        val areaTop = 128
        val areaBottom = HEIGHT - 64
        val panelGap = 22
        val headerH = 34
        val panelH = (areaBottom - areaTop - panelGap * (panels - 1)) / panels
        val weeks = r.weekStarts.size
        val groupW = (right - left).toDouble() / weeks
        val barW = groupW * 0.62

        for ((i, s) in r.series.withIndex()) {
            val pTop = areaTop + i * (panelH + panelGap)
            val plotTop = pTop + headerH + 22 // room for the value labels above the tallest bar
            val plotBottom = pTop + panelH
            val colour = SERIES[i % SERIES.size]

            // Panel header: swatch + label + this week's number (the series is named in text, not by colour alone).
            g.color = colour
            g.fill(RoundRectangle2D.Double(48.0, pTop + 6.0, 20.0, 20.0, 6.0, 6.0))
            g.font = bold.deriveFont(24f)
            g.color = TEXT
            g.drawString(s.label, 78, pTop + 25)
            val labelW = g.fontMetrics.stringWidth(s.label)
            g.font = regular.deriveFont(22f)
            g.color = TEXT_SECONDARY
            g.drawString("  ·  цього тижня ${s.current}, у середньому ${Barometer.decimal(s.mean)}", 78 + labelW, pTop + 25)

            // Y scale: at most three gridlines above zero in a panel this short.
            val maxValue = max(1, s.counts.maxOrNull() ?: 0)
            val step = niceStep(maxValue, maxTicks = 3)
            val yMax = step * ceil(maxValue.toDouble() / step).toInt()
            val plotH = (plotBottom - plotTop).toDouble()
            fun y(v: Int): Double = plotBottom - plotH * v / yMax

            g.font = regular.deriveFont(18f)
            val fm = g.fontMetrics
            var tick = 0
            while (tick <= yMax) {
                val ty = y(tick)
                if (tick > 0) {
                    g.color = GRID
                    g.stroke = BasicStroke(1f)
                    g.drawLine(left, ty.toInt(), right, ty.toInt())
                }
                g.color = TEXT_SECONDARY
                val label = tick.toString()
                g.drawString(label, left - 14 - fm.stringWidth(label), (ty + fm.ascent / 2.5).toInt())
                tick += step
            }

            // Bars: 4px rounded tops anchored on the baseline, value label above each non-zero bar.
            g.font = regular.deriveFont(18f)
            for (w in 0 until weeks) {
                val v = s.counts[w]
                if (v <= 0) continue
                val bx = left + groupW * w + (groupW - barW) / 2
                val by = y(v)
                g.color = colour
                g.fill(topRounded(bx, by, barW, plotBottom - by, 4.0))
                g.color = TEXT
                val sv = v.toString()
                val lw = g.fontMetrics.stringWidth(sv)
                g.drawString(sv, (bx + (barW - lw) / 2).toFloat(), (by - 5).toFloat())
            }

            // Zero baseline over the bar feet.
            g.color = BASELINE
            g.stroke = BasicStroke(2f)
            g.drawLine(left, plotBottom, right, plotBottom)
        }

        // Week labels once, under the last panel: the Monday; the current week in bold.
        for (w in 0 until weeks) {
            val isCurrent = w == weeks - 1
            g.font = if (isCurrent) bold.deriveFont(19f) else regular.deriveFont(19f)
            g.color = if (isCurrent) TEXT else TEXT_SECONDARY
            val wl = r.weekStarts[w].format(WEEK_LABEL)
            val ww = g.fontMetrics.stringWidth(wl)
            g.drawString(wl, (left + groupW * w + (groupW - ww) / 2).toFloat(), (areaBottom + 28).toFloat())
        }

        g.font = regular.deriveFont(16f)
        g.color = TEXT_SECONDARY
        g.drawString("Тиждень з понеділка; остання колонка — поточний тиждень; у кожної панелі своя шкала", 48, HEIGHT - 10)
    }

    /** A rectangle whose two top corners are rounded by [radius] and whose base is square. */
    private fun topRounded(x: Double, y: Double, w: Double, h: Double, radius: Double): Area {
        val r = minOf(radius, w / 2, h)
        val shape = Area(RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2))
        shape.add(Area(Rectangle2D.Double(x, y + r, w, h - r)))
        return shape
    }

    /** 1, 2, 5, 10, 20, 50, … — the smallest step giving at most [maxTicks] ticks up to [maxValue]. */
    internal fun niceStep(maxValue: Int, maxTicks: Int = 5): Int {
        var magnitude = 1
        while (true) {
            for (m in intArrayOf(1, 2, 5)) {
                val step = m * magnitude
                if (ceil(maxValue.toDouble() / step) <= maxTicks) return step
            }
            magnitude *= 10
        }
    }

    private fun loadFont(path: String, style: Int): Font = try {
        BarometerChart::class.java.getResourceAsStream(path).use { stream ->
            requireNotNull(stream) { "font $path missing" }
            Font.createFont(Font.TRUETYPE_FONT, stream)
        }
    } catch (e: Exception) {
        logger.warn(e) { "[Barometer] bundled font $path failed to load — using the JDK's SansSerif (Cyrillic may be missing)" }
        Font(Font.SANS_SERIF, style, 12)
    }
}
