package com.trytobatter.app.feature.petoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import com.trytobatter.app.feature.petpreview.*
import kotlinx.coroutines.*
import kotlin.math.abs

/** A real transparent system-window surface; no Activity or Compose host is needed to draw it. */
@SuppressLint("ViewConstructor")
internal class PetOverlayView(
    context: Context, private val tap: () -> Unit, private val drag: (Float, Float) -> Unit, private val drop: () -> Unit,
    private val failed: () -> Unit
) : View(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playback: Job? = null
    private var bitmap: android.graphics.Bitmap? = null
    private var clip = PetMotion.IDLE.clip()
    private var frame = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dest = RectF()
    private var animate = true
    private var awake = true
    private var quiet = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { contentDescription = "大肥鱼桌宠"; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun configure(animations: Boolean, sleeping: Boolean, visible: Boolean) {
        animate = animations; quiet = sleeping; awake = visible
        visibility = if (visible) VISIBLE else GONE
        play(if (sleeping) PetMotion.SLEEP else PetMotion.IDLE)
    }
    private fun play(next: PetMotion) {
        playback?.cancel()
        if (!awake) return
        playback = scope.launch {
            val nextClip = next.clip()
            val image = try { PetAtlasCache.load(context.applicationContext, nextClip.asset).asAndroidBitmap() }
                catch (_: java.io.IOException) { failed(); return@launch }
            clip = nextClip; bitmap = image
            frame = when { next == PetMotion.DRAG -> 10; !animate && next == PetMotion.SLEEP -> clip.durations.lastIndex; else -> 0 }
            invalidate()
            if (!animate || next == PetMotion.DRAG) return@launch
            do {
                clip.durations.forEachIndexed { index, duration -> frame = index; invalidate(); delay(duration.toLong()) }
            } while (clip.loop && next != PetMotion.TALK)
            if (next == PetMotion.TALK) play(if (quiet) PetMotion.SLEEP else PetMotion.IDLE)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); play(if (quiet) PetMotion.SLEEP else PetMotion.IDLE) }
    override fun onDetachedFromWindow() { scope.cancel(); bitmap = null; super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val x = frame % clip.columns * clip.cell; val y = frame / clip.columns * clip.cell
        src.set(x, y, x + clip.cell, y + clip.cell); dest.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(image, src, dest, paint)
    }
    override fun performClick(): Boolean { super.performClick(); play(if (quiet) PetMotion.SLEEP else PetMotion.TALK); tap(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) { dragging = true; play(PetMotion.DRAG) }
                if (dragging) drag(event.rawX - lastX, event.rawY - lastY)
                lastX = event.rawX; lastY = event.rawY; return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { drop(); play(if (quiet) PetMotion.SLEEP else PetMotion.IDLE) }
                else if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }
}
