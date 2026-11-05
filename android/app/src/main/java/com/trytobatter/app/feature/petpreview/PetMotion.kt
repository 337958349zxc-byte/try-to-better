package com.trytobatter.app.feature.petpreview

import kotlin.math.max

enum class PetMotion(val label: String, val phrase: String) {
    IDLE("待机", "有事就说嘛，我听着呢。"),
    WALK("走路", "走两步，换个心情。"),
    THINK("思考", "等一下，让我想想……"),
    TALK("对话", "今天想先聊哪件事？"),
    HAPPY("开心", "一点点进步，也值得开心！"),
    REMIND("提醒", "休息一下，再看看今天的安排吧。"),
    SLEEP("睡觉", "我先安静陪着你。"),
    DRAG("拖动", "慢一点，尾巴也要一起带走。")
}

internal data class PetClip(val asset: String, val cell: Int, val columns: Int, val durations: List<Int>, val loop: Boolean = false) {
    val durationMs: Int get() = durations.sum()
    fun frameAt(elapsedMs: Long): Int {
        var time = if (loop) elapsedMs.coerceAtLeast(0) % durationMs else elapsedMs.coerceAtLeast(0)
        durations.forEachIndexed { index, duration ->
            if (time < duration) return index
            time -= duration
        }
        return durations.lastIndex
    }
}

/** Complete upstream poses; never animate by deforming or rotating body parts. */
internal fun PetMotion.clip(): PetClip = when (this) {
    PetMotion.IDLE -> PetClip("idle", 512, 2, listOf(5200, 200), loop = true)
    PetMotion.WALK -> PetClip("walk", 384, 4, List(16) { 60 }, loop = true)
    PetMotion.THINK -> PetClip("think", 384, 3, listOf(110, 130, 180))
    PetMotion.TALK -> PetClip("talk", 384, 4, List(16) { 90 }, loop = true)
    PetMotion.HAPPY -> PetClip("happy", 384, 4, List(13) { 85 } + listOf(90, 100, 140), loop = true)
    PetMotion.REMIND -> PetClip("remind", 384, 4, listOf(65, 85, 45, 45, 40, 40, 40, 40, 50, 65, 65, 50, 45, 45, 65, 70, 40, 40, 40, 40, 40, 40, 100))
    PetMotion.SLEEP -> PetClip("sleep", 384, 4, listOf(130, 170, 170, 65, 65, 120, 65, 65, 70, 70, 75, 75, 180, 150, 180, 180, 180, 180, 220))
    PetMotion.DRAG -> PetClip("remind", 384, 4, listOf(1))
}

internal data class PetBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun x(value: Float) = value.coerceIn(left, right)
    fun y(value: Float) = value.coerceIn(top, bottom)
    fun dock(value: Float): Float = if (value < (left + right) / 2) left else right
}

/** Coordinates in dp, inside the app's already inset content area. */
internal fun petBounds(width: Float, height: Float, size: Float) = PetBounds(
    left = 8f, top = 76f.coerceAtMost(max(8f, height - size - 8f)),
    right = max(8f, width - size - 8f),
    bottom = max(76f.coerceAtMost(max(8f, height - size - 8f)), height - size - 136f)
)
