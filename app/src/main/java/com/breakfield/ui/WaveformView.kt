package com.breakfield.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.breakfield.audio.Engine
import com.breakfield.model.Clip
import kotlin.math.max
import kotlin.math.min

/** Multi-pointer surface. X = loop start, Y = loop duration. SPLIT mode adds slice markers. */
class WaveformView(context: Context, private val engine: Engine) : View(context) {
    var editSlices = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touches = LinkedHashMap<Int, Pair<Float,Float>>()
    private val teal = Color.rgb(43, 208, 180)
    private val slate = Color.rgb(135, 155, 175)
    private val bg = Color.rgb(24, 30, 39)
    private fun clip(): Clip? = engine.clips.getOrNull(engine.selectedClip)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(bg)
        paint.strokeWidth = 1f
        paint.color = Color.argb(36, 255,255,255)
        for (i in 0..16) {
            val x = w * i / 16f
            canvas.drawLine(x, 0f, x, h, paint)
        }
        canvas.drawLine(0f, h/2, w, h/2, paint)
        val clip = clip()
        if (clip == null) {
            paint.color = slate
            paint.textSize = 16f * resources.displayMetrics.scaledDensity
            canvas.drawText("Load a WAV loop to play this surface", 16f, h/2, paint)
        } else {
            val pcm = clip.pcm
            if (pcm.isNotEmpty()) {
                val columns = max(1, w.toInt())
                for (column in 0 until columns) {
                    val start = (column.toLong() * pcm.size / columns).toInt().coerceIn(0, pcm.lastIndex)
                    val end = min(pcm.size, ((column+1L) * pcm.size / columns).toInt().coerceAtLeast(start+1))
                    var low = 0f; var high = 0f
                    val stride = max(1, (end-start)/48)
                    for (i in start until end step stride) {
                        low = min(low, pcm[i]); high = max(high, pcm[i])
                    }
                    paint.color = slate
                    paint.strokeWidth = 1.6f
                    val x = column.toFloat()
                    canvas.drawLine(x, h/2-high*h*.43f, x, h/2-low*h*.43f, paint)
                }
                paint.strokeWidth = 1.3f
                for (slice in clip.slices) {
                    paint.color = if (editSlices) Color.rgb(255,170,90) else Color.argb(130,45,210,180)
                    val x = slice.start.toFloat() / pcm.size * w
                    canvas.drawLine(x, 0f, x, h, paint)
                }
            }
        }
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        paint.color = Color.WHITE
        canvas.drawText(if (editSlices) "EDIT: Tap to split slices" else "PLAY: X start  •  Y length", 12f, 22f * resources.displayMetrics.density, paint)
        for ((_,coords) in touches) {
            val x = coords.first * w
            val y = coords.second * h
            val len = min(1f - coords.first, ((1f - coords.second) * (1f - coords.second))) * w
            paint.color = Color.argb(55, 43,208,180)
            canvas.drawRect(x, 0f, x+len, h, paint)
            paint.color = teal
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * resources.displayMetrics.density
            canvas.drawCircle(x, y, 12f * resources.displayMetrics.density, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (clip() == null) return true
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val x = (event.getX(index)/width.coerceAtLeast(1)).coerceIn(0f, .999f)
        val y = (event.getY(index)/height.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!editSlices) {
                    touches[id] = x to y
                    engine.triggerTouch(id, x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(i)
                    if (pointer in touches) {
                        val px = (event.getX(i)/width.coerceAtLeast(1)).coerceIn(0f,.999f)
                        val py = (event.getY(i)/height.coerceAtLeast(1)).coerceIn(0f,1f)
                        touches[pointer] = px to py
                        engine.moveTouch(pointer,px,py)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (editSlices) clip()?.let { it.addSlice((x * it.pcm.size).toInt()) }
                else { touches.remove(id); engine.releaseTouch(id) }
            }
            MotionEvent.ACTION_CANCEL -> {
                touches.clear(); engine.releaseAllTouches()
            }
        }
        invalidate()
        return true
    }
}
