package com.aybrkaknc.songshare.lyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.aybrkaknc.songshare.ui.Thumbnails
import java.io.File

// Square images for the lyrics share: cover top left, artist and title beside it, lyrics below.
// renderFrame shows a three line window for the video; renderCard shows every checked line as one still image.
object LyricsCard {
    private const val SIZE = 1080
    private const val PAD = 90f
    private const val COVER = 260f
    private const val GAP = 40f
    private const val WINDOW_TOP = 420f
    private const val LINE_GAP = 36f
    private const val CURRENT_PX = 64f
    private const val SIDE_PX = 40f
    private const val CARD_PX = 56f
    private const val MIN_PX = 28f
    private const val DIM = 0xFF6B6B6B.toInt()

    // YouTube's small preview is blurry at this size; the larger one usually exists too.
    suspend fun loadCover(url: String?): Bitmap? =
        url?.let { Thumbnails.load(it.replace("mqdefault", "hqdefault")) ?: Thumbnails.load(it) }

    // lines[index] is the current line (bold, already highlighted). progress runs 0..1 while the window slides up from the
    // previous line to this one; 1 is the resting state with the current line in the middle. Lines more than one step
    // from the middle fade out, so at rest only the previous, current and next lines show.
    fun renderFrame(cover: Bitmap?, artist: String, title: String, lines: List<String>, index: Int, progress: Float, out: File) {
        val bmp = newImage(cover, artist, title)
        val c = Canvas(bmp)
        val width = (SIZE - 2 * PAD).toInt()
        val room = SIZE - PAD - WINDOW_TOP
        val rows = (index - 2..index + 1).filter { it in lines.indices }
        val layouts = rows.associateWith { i ->
            if (i == index) {
                layout(lines[i], paint(CURRENT_PX, Typeface.DEFAULT_BOLD, 0xFFFFFFFF.toInt()), width)
            } else {
                layout(lines[i], paint(SIDE_PX, Typeface.DEFAULT, DIM), width)
            }
        }
        // Rows stacked top to bottom; the window centers on a point between the previous and the current row.
        val tops = HashMap<Int, Float>()
        var y = 0f
        rows.forEach { i ->
            tops[i] = y
            y += layouts.getValue(i).height + LINE_GAP
        }
        fun center(i: Int) = tops.getValue(i) + layouts.getValue(i).height / 2f
        val anchor = if (index - 1 in tops) center(index - 1) + (center(index) - center(index - 1)) * progress else center(index)
        val shift = WINDOW_TOP + room / 2 - anchor
        val focus = progress - 1f
        c.save()
        c.clipRect(0f, WINDOW_TOP, SIZE.toFloat(), SIZE - PAD)
        rows.forEach { i ->
            val alpha = (2f - kotlin.math.abs((i - index) - focus)).coerceIn(0f, 1f)
            if (alpha <= 0f) return@forEach
            val l = layouts.getValue(i)
            l.paint.alpha = (alpha * 255).toInt()
            draw(c, l, shift + tops.getValue(i))
        }
        c.restore()
        save(bmp, out)
    }

    fun renderCard(cover: Bitmap?, artist: String, title: String, lines: List<String>, out: File) {
        val bmp = newImage(cover, artist, title)
        val c = Canvas(bmp)
        val width = (SIZE - 2 * PAD).toInt()
        val room = SIZE - PAD - WINDOW_TOP
        val text = lines.joinToString("\n")
        var size = CARD_PX
        var layout: StaticLayout
        while (true) {
            layout = layout(text, paint(size, Typeface.DEFAULT_BOLD, 0xFFFFFFFF.toInt()), width)
            if (layout.height <= room || size <= MIN_PX) break
            size -= 2f
        }
        draw(c, layout, WINDOW_TOP)
        save(bmp, out)
    }

    // Dark background, cover and the artist and title beside it. The same on every image.
    private fun newImage(cover: Bitmap?, artist: String, title: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), 0xFF26262B.toInt(), 0xFF050505.toInt(), Shader.TileMode.CLAMP)
        })
        val coverBox = RectF(PAD, PAD, PAD + COVER, PAD + COVER)
        if (cover != null) {
            c.save()
            c.clipPath(Path().apply { addRoundRect(coverBox, 20f, 20f, Path.Direction.CW) })
            c.drawBitmap(cover, centerCrop(cover), coverBox, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }
        val textX = if (cover != null) coverBox.right + GAP else PAD
        val textW = SIZE - PAD - textX
        val artistPaint = paint(36f, Typeface.DEFAULT, 0xFFA8A8A8.toInt())
        val titlePaint = paint(56f, Typeface.DEFAULT_BOLD, 0xFFFFFFFF.toInt())
        val artistH = if (artist.isBlank()) 0f else artistPaint.descent() - artistPaint.ascent() + 12f
        val titleH = titlePaint.descent() - titlePaint.ascent()
        var y = coverBox.centerY() - (artistH + titleH) / 2
        if (artist.isNotBlank()) {
            c.drawText(ellipsize(artist, artistPaint, textW), textX, y - artistPaint.ascent(), artistPaint)
            y += artistH
        }
        c.drawText(ellipsize(title, titlePaint, textW), textX, y - titlePaint.ascent(), titlePaint)
        return bmp
    }

    private fun save(bmp: Bitmap, out: File) {
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun paint(size: Float, face: Typeface, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        this.color = color
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float) =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(paint.textSize * 0.2f, 1f)
            .build()

    private fun draw(c: Canvas, layout: StaticLayout, top: Float) {
        c.save()
        c.translate(PAD, top)
        layout.draw(c)
        c.restore()
    }

    private fun centerCrop(b: Bitmap): Rect {
        val side = minOf(b.width, b.height)
        val left = (b.width - side) / 2
        val top = (b.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }
}
