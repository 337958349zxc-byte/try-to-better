package com.trytobatter.app.feature.petoverlay

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

internal class PetOverlayConfig(context: Context) {
    private val prefs = context.getSharedPreferences("pet_overlay_v1", Context.MODE_PRIVATE)
    var size: Int
        get() = prefs.getInt("size", 176).coerceIn(112, 208)
        set(value) { prefs.edit { putInt("size", value.coerceIn(112, 208)) } }
    var animations: Boolean
        get() = prefs.getBoolean("animations", true)
        set(value) { prefs.edit { putBoolean("animations", value) } }
    var quiet: Boolean
        get() = prefs.getBoolean("quiet", false)
        set(value) { prefs.edit { putBoolean("quiet", value) } }
    var left: Boolean
        get() = prefs.getBoolean("left", false)
        set(value) { prefs.edit { putBoolean("left", value) } }
    var vertical: Float
        get() = prefs.getFloat("vertical", .72f).coerceIn(0f, 1f)
        set(value) { prefs.edit { putFloat("vertical", value.coerceIn(0f, 1f)) } }
}

internal data class OverlayStatus(val running: Boolean = false, val message: String = "尚未开启")
internal object PetOverlayRuntime {
    private val mutable = MutableStateFlow(OverlayStatus())
    val status = mutable.asStateFlow()
    fun set(running: Boolean, message: String) { mutable.value = OverlayStatus(running, message) }
}

/** Full-screen pixel coordinates after system bars, cutouts and gesture-edge clearance. */
internal data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun x(value: Int) = value.coerceIn(left, right)
    fun y(value: Int) = value.coerceIn(top, bottom)
}
internal fun overlayBounds(width: Int, height: Int, insetLeft: Int, insetTop: Int, insetRight: Int, insetBottom: Int, size: Int, gap: Int): OverlayBounds {
    val left = insetLeft + gap
    val top = insetTop + gap
    return OverlayBounds(left, top, max(left, width - insetRight - gap - size), max(top, height - insetBottom - gap - size))
}
