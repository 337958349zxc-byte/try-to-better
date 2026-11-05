package com.trytobatter.app.feature.workout

import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trytobatter.app.core.designsystem.*
import android.util.Size
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@Composable fun CameraPanel(cameraEnabled: Boolean, modifier: Modifier = Modifier, onCameraError: (String) -> Unit,
    analyzeEnabled:Boolean=true,onPose:(PoseFrame)->Unit={}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val currentPose by rememberUpdatedState(onPose)
    val currentError by rememberUpdatedState(onCameraError)
    val currentAnalysis by rememberUpdatedState(analyzeEnabled)
    var frame by remember {mutableStateOf<PoseFrame?>(null)}
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(cameraEnabled, owner) {
        val active=AtomicBoolean(true)
        var analysis:ImageAnalysis?=null
        var analyzer:PoseCameraAnalyzer?=null
        var executor:java.util.concurrent.ExecutorService?=null
        if(cameraEnabled && ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ if(active.get()) runCatching {
                val p = future.get(); provider = p
                val front=p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector = if(front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val preview = Preview.Builder().setTargetResolution(Size(640,480)).build().also { it.surfaceProvider = previewView.surfaceProvider }
                val worker=Executors.newSingleThreadExecutor();executor=worker
                val main=ContextCompat.getMainExecutor(context)
                val detector=PoseCameraAnalyzer(context,front,{result -> main.execute {if(active.get()) {frame=result;currentPose(result)}}},
                    {message -> main.execute {if(active.get()) currentError(message)}});analyzer=detector
                val frames=ImageAnalysis.Builder().setTargetResolution(Size(640,480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                frames.setAnalyzer(worker) {image -> if(active.get() && currentAnalysis) detector.analyze(image) else image.close()};analysis=frames
                p.unbindAll(); p.bindToLifecycle(owner, selector, preview,frames)
            }.onFailure { currentError("摄像头暂不可用，可继续手工记录：${it.message}") } }, ContextCompat.getMainExecutor(context))
        }
        onDispose {active.set(false);analysis?.clearAnalyzer();provider?.unbindAll();executor?.execute {analyzer?.close()};executor?.shutdown()}
    }
    Box(modifier.background(Color(0xFF182433)), contentAlignment = Alignment.Center) {
        if(cameraEnabled) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        else Canvas(Modifier.fillMaxSize().padding(28.dp)) {
            val center = size.width * .5f
            val head = Offset(center, size.height * .17f)
            val neck = Offset(center, size.height * .28f)
            val hip = Offset(center, size.height * .53f)
            val color = Color(0xFF57718E)
            drawCircle(color, size.minDimension * .065f, head)
            fun line(a: Offset, b: Offset) = drawLine(color, a, b, 7f, StrokeCap.Round)
            line(neck, hip)
            line(Offset(center - size.width * .17f, size.height * .32f), Offset(center + size.width * .17f, size.height * .32f))
            line(Offset(center - size.width * .17f, size.height * .32f), Offset(center - size.width * .25f, size.height * .51f))
            line(Offset(center + size.width * .17f, size.height * .32f), Offset(center + size.width * .25f, size.height * .51f))
            line(hip, Offset(center - size.width * .15f, size.height * .82f))
            line(hip, Offset(center + size.width * .15f, size.height * .82f))
        }
        if(cameraEnabled && analyzeEnabled) frame?.let {result -> Canvas(Modifier.fillMaxSize()) {
            val scale=min(size.width/result.width,size.height/result.height)
            val dx=(size.width-result.width*scale)/2;val dy=(size.height-result.height*scale)/2
            val edges=listOf(11 to 12,11 to 13,13 to 15,12 to 14,14 to 16,11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,24 to 26,26 to 28)
            for((a,b) in edges) if(result.points.size>maxOf(a,b)) {
                val p=result.points[a];val q=result.points[b]
                if(p.confidence>=.55f && q.confidence>=.55f) drawLine(Color(0xFF64FFDA),Offset(dx+p.x*result.width*scale,dy+p.y*result.height*scale),Offset(dx+q.x*result.width*scale,dy+q.y*result.height*scale),4f,StrokeCap.Round)
            }
        }}
        Text(if(!cameraEnabled) "人体入镜示意" else if(!analyzeEnabled) "识别已暂停" else if(frame==null) "正在加载本机识别…" else if(frame?.points.isNullOrEmpty()) "未识别到完整身体" else "实时姿态识别", Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(8.dp),
            color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
