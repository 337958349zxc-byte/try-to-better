package com.trytobatter.app.feature.petpreview

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal object PetAtlasCache {
    private val cache = object : LruCache<String, ImageBitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    suspend fun load(context: Context, name: String): ImageBitmap = withContext(Dispatchers.IO) {
        cache.get(name) ?: context.assets.open("petpreview/$name.webp").use { stream ->
            requireNotNull(BitmapFactory.decodeStream(stream)).asImageBitmap().also { cache.put(name, it) }
        }
    }
}

@Composable internal fun PetArtwork(
    motion: PetMotion, animate: Boolean, modifier: Modifier = Modifier, facingLeft: Boolean = false,
    onCycleEnd: () -> Unit = {}
) {
    val clip = remember(motion) { motion.clip() }
    val context = LocalContext.current.applicationContext
    var loadFailed by remember(clip.asset) { mutableStateOf(false) }
    val atlas by produceState<ImageBitmap?>(null, clip.asset) {
        value = try { PetAtlasCache.load(context, clip.asset) } catch (_: java.io.IOException) { loadFailed = true; null }
    }
    var frame by remember(motion, animate) { mutableIntStateOf(when {
        motion == PetMotion.DRAG -> 10
        !animate && motion == PetMotion.SLEEP -> clip.durations.lastIndex
        !animate && motion == PetMotion.THINK -> 2
        else -> 0
    }) }
    val end by rememberUpdatedState(onCycleEnd)
    LaunchedEffect(motion, animate, atlas) {
        if (!animate || atlas == null || motion == PetMotion.DRAG) return@LaunchedEffect
        do {
            for (index in clip.durations.indices) {
                frame = index
                delay(clip.durations[index].toLong())
            }
        } while (clip.loop)
        end()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = atlas
        if (bitmap != null) Canvas(Modifier.fillMaxSize()) {
            // Full square cell, including original transparent padding: never crop hair or tail.
            scale(if (facingLeft) -1f else 1f, 1f) {
                drawImage(bitmap, srcOffset = IntOffset(frame % clip.columns * clip.cell, frame / clip.columns * clip.cell),
                    srcSize = IntSize(clip.cell, clip.cell), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
        } else if (loadFailed) Text("素材读取失败") else CircularProgressIndicator()
    }
}

@Composable internal fun PetAvatar(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val atlas by produceState<ImageBitmap?>(null) { value = PetAtlasCache.load(context, "portrait") }
    atlas?.let { bitmap -> Canvas(modifier) {
        // Avatar-only head/shoulder crop from the existing portrait; stage art always stays full-body.
        drawImage(bitmap, srcOffset = IntOffset(36, 16), srcSize = IntSize(112, 112),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    } }
}
