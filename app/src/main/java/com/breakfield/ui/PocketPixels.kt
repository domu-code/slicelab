package com.breakfield.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.Button
import android.widget.TextView
import kotlin.math.min

/**
 * BREAKFIELD's pixel typography uses the five-by-five glyph designs from the user's
 * PocketTracker3 R14 source (native/ui/font5x5.h). No TTF/OTF font asset is bundled.
 * The PocketTracker project is GPL-3.0 licensed; retain attribution and check
 * obligations before redistributing modified versions.
 */
object PocketPixels {
    private val glyphs = mapOf(
        '0' to intArrayOf(0b11111, 0b10001, 0b10101, 0b10001, 0b11111),
        '1' to intArrayOf(0b01100, 0b10100, 0b00100, 0b00100, 0b11111),
        '2' to intArrayOf(0b11110, 0b00001, 0b00110, 0b01000, 0b11111),
        '3' to intArrayOf(0b11111, 0b00001, 0b01111, 0b00001, 0b11111),
        '4' to intArrayOf(0b10001, 0b10001, 0b11111, 0b00001, 0b00001),
        '5' to intArrayOf(0b11111, 0b10000, 0b11111, 0b00001, 0b11111),
        '6' to intArrayOf(0b11111, 0b10000, 0b11111, 0b10001, 0b11111),
        '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000),
        '8' to intArrayOf(0b11111, 0b10001, 0b11111, 0b10001, 0b11111),
        '9' to intArrayOf(0b11111, 0b10001, 0b11111, 0b00001, 0b11111),
        'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001),
        'B' to intArrayOf(0b11110, 0b10001, 0b11110, 0b10001, 0b11110),
        'C' to intArrayOf(0b01111, 0b10000, 0b10000, 0b10000, 0b01111),
        'D' to intArrayOf(0b11110, 0b10001, 0b10001, 0b10001, 0b11110),
        'E' to intArrayOf(0b11111, 0b10000, 0b11110, 0b10000, 0b11111),
        'F' to intArrayOf(0b11111, 0b10000, 0b11110, 0b10000, 0b10000),
        'G' to intArrayOf(0b01111, 0b10000, 0b10111, 0b10001, 0b01111),
        'H' to intArrayOf(0b10001, 0b10001, 0b11111, 0b10001, 0b10001),
        'I' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b11111),
        'J' to intArrayOf(0b00001, 0b00001, 0b10001, 0b10001, 0b01110),
        'K' to intArrayOf(0b10001, 0b10010, 0b11100, 0b10010, 0b10001),
        'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
        'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001),
        'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001),
        'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b01110),
        'P' to intArrayOf(0b11110, 0b10001, 0b11110, 0b10000, 0b10000),
        'Q' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10010, 0b01101),
        'R' to intArrayOf(0b11110, 0b10001, 0b11110, 0b10010, 0b10001),
        'S' to intArrayOf(0b01111, 0b10000, 0b01110, 0b00001, 0b11110),
        'T' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100),
        'U' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
        'V' to intArrayOf(0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
        'W' to intArrayOf(0b10001, 0b10101, 0b10101, 0b10101, 0b01110),
        'X' to intArrayOf(0b10001, 0b01010, 0b00100, 0b01010, 0b10001),
        'Y' to intArrayOf(0b10001, 0b10001, 0b01110, 0b00100, 0b00100),
        'Z' to intArrayOf(0b11111, 0b00010, 0b00100, 0b01000, 0b11111),
        '_' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b11111),
        '-' to intArrayOf(0b00000, 0b00000, 0b11111, 0b00000, 0b00000),
        '#' to intArrayOf(0b01010, 0b11111, 0b01010, 0b11111, 0b01010),
        '.' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00100),
        ',' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00100, 0b00100),
        ':' to intArrayOf(0b00000, 0b00100, 0b00000, 0b00100, 0b00000),
        '/' to intArrayOf(0b00001, 0b00010, 0b00100, 0b01000, 0b10000),
        '%' to intArrayOf(0b10001, 0b00010, 0b00100, 0b01000, 0b10001),
        '+' to intArrayOf(0b00100, 0b00100, 0b11111, 0b00100, 0b00100),
        '<' to intArrayOf(0b00010, 0b00100, 0b01000, 0b00100, 0b00010),
        '>' to intArrayOf(0b01000, 0b00100, 0b00010, 0b00100, 0b01000),
        '=' to intArrayOf(0b00000, 0b11111, 0b00000, 0b11111, 0b00000),
        '[' to intArrayOf(0b00110, 0b00100, 0b00100, 0b00100, 0b00110),
        '(' to intArrayOf(0b00010, 0b00100, 0b00100, 0b00100, 0b00010),
        '!' to intArrayOf(0b00100, 0b00100, 0b00100, 0b00000, 0b00100),
        '?' to intArrayOf(0b01110, 0b00010, 0b00100, 0b00000, 0b00100),
        ']' to intArrayOf(0b01100, 0b00100, 0b00100, 0b00100, 0b01100),
        ')' to intArrayOf(0b01000, 0b00100, 0b00100, 0b00100, 0b01000),
        '|' to intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100),
        '\"' to intArrayOf(0b01010, 0b00000, 0b00000, 0b00000, 0b00000),
        '*' to intArrayOf(0b00100, 0b10101, 0b01110, 0b10101, 0b00100),
        ' ' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000),
    )
    fun supports(text: String): Boolean = text.uppercase().all { it in glyphs || it == ' ' || it == '•' }
    fun draw(canvas: Canvas, label: String, left: Float, top: Float, unit: Float, color: Int) {
        val paint = Paint().apply { this.color = color; isAntiAlias = false; style = Paint.Style.FILL }
        var x = left
        for (c in label.uppercase()) {
            val rows = glyphs[if (c == '•') '*' else c]
            if (rows != null) for (r in 0..4) for (col in 0..4) {
                if ((rows[r] and (1 shl (4 - col))) != 0) {
                    canvas.drawRect(x + col*unit, top+r*unit, x+(col+1)*unit,top+(r+1)*unit,paint)
                }
            }
            x += unit * 6f
        }
    }
}

/** Pixel headings preserve TextView text semantics and accessibility. */
class PocketTextView(context: Context) : TextView(context) {
    override fun onDraw(canvas: Canvas) {
        val label = text?.toString().orEmpty()
        if (label.isEmpty() || !PocketPixels.supports(label) || label.contains('\n')) {
            super.onDraw(canvas)
            return
        }
        val freeWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val scale = min(textSize / 7f, freeWidth / (label.length * 6f)).coerceAtLeast(0.75f)
        val glyphHeight = scale * 5f
        val top = paddingTop + ((height - paddingTop - paddingBottom - glyphHeight) / 2f)
        val x = paddingLeft.toFloat()
        PocketPixels.draw(canvas, label, x, top, scale, currentTextColor)
    }
}

/** Native Android button background and hit target with PocketTracker pixel lettering. */
class PocketButton(context: Context) : Button(context) {
    private var pixelInk: Int = Color.WHITE
    fun usePixelInk(color: Int) { pixelInk = color; setTextColor(Color.TRANSPARENT) }
    override fun onDraw(canvas: Canvas) {
        val label = text?.toString().orEmpty()
        if (label.isEmpty() || !PocketPixels.supports(label) || label.contains('\n')) {
            paint.color = pixelInk
            super.onDraw(canvas)
            paint.color = Color.TRANSPARENT
            return
        }
        super.onDraw(canvas)
        val freeWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val maxSize = min(textSize / 7f, (height-paddingTop-paddingBottom) / 8f)
        val scale = min(maxSize, freeWidth / (label.length * 6f)).coerceAtLeast(.65f)
        val glyphWidth = (label.length*6-1)*scale
        val x = ((width-glyphWidth)/2f).coerceAtLeast(0f)
        val y = (height-5*scale)/2f
        PocketPixels.draw(canvas, label, x, y, scale, pixelInk)
    }
}
