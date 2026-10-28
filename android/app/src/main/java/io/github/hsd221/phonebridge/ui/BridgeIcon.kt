package io.github.hsd221.phonebridge.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small, platform-independent icon set with the same 24-unit grid and stroke weight. */
enum class BridgeIcon { LINK, SHIELD, SETTINGS }

class BridgeIconDrawable(private val icon: BridgeIcon, tint: Int, private val pixels: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = pixels
    override fun getIntrinsicHeight() = pixels
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (icon) {
            BridgeIcon.LINK -> {
                canvas.rotate(-42f, 12f, 12f)
                val path = Path().apply {
                    moveTo(10f, 8f); lineTo(7f, 8f); cubicTo(1f, 8f, 1f, 16f, 7f, 16f); lineTo(10f, 16f)
                    moveTo(14f, 8f); lineTo(17f, 8f); cubicTo(23f, 8f, 23f, 16f, 17f, 16f); lineTo(14f, 16f)
                    moveTo(8f, 12f); lineTo(16f, 12f)
                }
                canvas.drawPath(path, paint)
            }
            BridgeIcon.SHIELD -> {
                val path = Path().apply {
                    moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f)
                    cubicTo(20f, 17f, 16f, 20f, 12f, 22f); cubicTo(8f, 20f, 4f, 17f, 4f, 12f)
                    lineTo(4f, 6f); close()
                    moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
                }
                canvas.drawPath(path, paint)
            }
            BridgeIcon.SETTINGS -> {
                canvas.drawLine(3f, 6f, 7f, 6f, paint); canvas.drawLine(13f, 6f, 21f, 6f, paint)
                canvas.drawCircle(10f, 6f, 3f, paint)
                canvas.drawLine(3f, 18f, 11f, 18f, paint); canvas.drawLine(17f, 18f, 21f, 18f, paint)
                canvas.drawCircle(14f, 18f, 3f, paint)
            }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
