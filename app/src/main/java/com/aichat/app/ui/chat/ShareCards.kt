package com.aichat.app.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role

/** 台词卡 / 场景长图：画到 Bitmap，写入相册，调起系统分享。 */
object ShareCards {
    private const val W = 1080
    private val BG = Color.parseColor("#09090B")
    private val SURFACE = Color.parseColor("#18181B")
    private val FG = Color.parseColor("#FAFAFA")
    private val FG2 = Color.parseColor("#A1A1AA")
    private val FG3 = Color.parseColor("#71717A")
    private val ACCENT = Color.parseColor("#A78BFA")

    fun shareCard(ctx: Context, world: String, name: String, emoji: String, line: String) {
        val bmp = card(world, name, emoji, plain(line).take(160))
        share(ctx, bmp, "dialogue-card.png")
    }

    fun shareScene(ctx: Context, world: String, name: String, emoji: String, messages: List<Message>) {
        val bmp = scene(world, name, emoji, messages)
        share(ctx, bmp, "scene.png")
    }

    private fun card(world: String, name: String, emoji: String, line: String): Bitmap {
        val h = 1920
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val accent = paint(ACCENT)
        c.drawRect(0f, 0f, W.toFloat(), 8f, accent)
        text(c, world.ifBlank { "AI Chat" }, 72f, 180f, 28f, FG3)
        val circle = paint(SURFACE)
        c.drawCircle(W / 2f, 520f, 96f, circle)
        text(c, emoji.ifBlank { "✦" }, W / 2f - 28f, 545f, 64f, FG)
        text(c, name.ifBlank { "角色" }, 72f, 700f, 56f, FG, bold = true)
        val box = RectF(72f, 780f, W - 72f, 1480f)
        c.drawRoundRect(box, 36f, 36f, circle)
        val lines = wrap(line, paint(FG, 40f), W - 200f)
        var y = 860f
        lines.take(10).forEach { text(c, it, 108f, y, 40f, FG); y += 64f }
        text(c, "AI Chat", 72f, 1780f, 24f, FG3)
        return bmp
    }

    private fun scene(world: String, name: String, emoji: String, messages: List<Message>): Bitmap {
        val pad = 72f
        val maxW = W - pad * 2
        val blocks = ArrayList<Pair<Boolean, List<String>>>()
        messages.forEach { m ->
            val user = m.role == Role.USER
            val body = plain(m.content).take(400)
            if (body.isBlank()) return@forEach
            blocks += user to wrap(body, paint(FG, 32f), maxW - 80f)
        }
        var h = 280
        blocks.forEach { h += 40 + it.second.size * 48 + 36 }
        h = h.coerceIn(800, 6000)
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        text(c, world.ifBlank { "场景" }, pad, 120f, 28f, FG3)
        text(c, "$emoji ${name.ifBlank { "角色" }}", pad, 190f, 44f, FG, bold = true)
        var y = 260f
        blocks.forEach { (user, lines) ->
            val bh = 28f + lines.size * 48f
            val box = if (user) RectF(W - pad - 620f, y, W - pad, y + bh) else RectF(pad, y, pad + 720f, y + bh)
            c.drawRoundRect(box, 28f, 28f, paint(if (user) ACCENT else SURFACE))
            var ty = y + 40f
            lines.forEach { text(c, it, box.left + 28f, ty, 32f, if (user) Color.WHITE else FG); ty += 48f }
            y += bh + 28f
        }
        return bmp
    }

    private fun share(ctx: Context, bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AIChat")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun paint(color: Int, size: Float = 32f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size }
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        c.drawText(s, x, y, paint(color, size).apply { if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD })
    }
    private fun wrap(text: String, p: Paint, width: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        text.forEach { ch ->
            val next = line + ch
            if (p.measureText(next) > width && line.isNotEmpty()) { out += line; line = ch.toString() } else line = next
        }
        if (line.isNotEmpty()) out += line
        return out
    }
    private fun plain(s: String) = s.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()
}
