package com.trytobatter.app.feature.workout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer

/** Bundled model, synchronous worker-thread inference, no frame storage or network calls. */
class LocalPoseDetector(context:Context):AutoCloseable {
    private val task=PoseLandmarker.createFromOptions(context,PoseLandmarker.PoseLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.CPU).build())
        .setRunningMode(RunningMode.VIDEO).setNumPoses(1).setMinPoseDetectionConfidence(.5f)
        .setMinPosePresenceConfidence(.5f).setMinTrackingConfidence(.5f).build())
    /** Takes ownership of this frame: MPImage.close() recycles its bitmap. */
    fun detect(bitmap:Bitmap,timestamp:Long):PoseFrame {
        val image=BitmapImageBuilder(bitmap).build()
        try {
            val landmarks=task.detectForVideo(image,timestamp).landmarks().firstOrNull().orEmpty()
            return PoseFrame(landmarks.map {PosePoint(it.x(),it.y(),it.visibility().orElse(0f))},bitmap.width,bitmap.height,timestamp)
        } finally {image.close()}
    }
    override fun close()=task.close()
}

class PoseCameraAnalyzer(private val context:Context,private val mirrored:Boolean,
    private val onFrame:(PoseFrame)->Unit,private val onError:(String)->Unit):ImageAnalysis.Analyzer,AutoCloseable {
    private var detector:LocalPoseDetector?=null
    private var buffer:Bitmap?=null
    private var pixels=ByteArray(0)
    private var lastFrame=-100L
    private var failed=false
    override fun analyze(image:ImageProxy) {
        var rotated:Bitmap?=null
        try {
            val now=SystemClock.elapsedRealtime()
            if(failed || now-lastFrame<100) return
            lastFrame=now
            if(buffer?.isRecycled!=false || buffer?.width!=image.width || buffer?.height!=image.height) {
                buffer?.recycle();buffer=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
                pixels=ByteArray(image.width*image.height*4)
            }
            val plane=image.planes[0];check(plane.pixelStride==4) {"摄像头颜色格式不支持"}
            val source=plane.buffer.duplicate();val start=source.position();val rowBytes=image.width*4
            for(row in 0 until image.height) {source.position(start+row*plane.rowStride);source.get(pixels,row*rowBytes,rowBytes)}
            val bitmap=buffer!!;bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            val matrix=Matrix().apply {postRotate(image.imageInfo.rotationDegrees.toFloat());if(mirrored) postScale(-1f,1f)}
            rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            val engine=detector ?: LocalPoseDetector(context).also {detector=it}
            onFrame(engine.detect(rotated,now))
        } catch(e:Exception) {failed=true;onError("姿态识别未能启动，请重新打开相机。${e.message.orEmpty().take(100)}")}
        finally {if(rotated!=null && rotated!==buffer) rotated.recycle();image.close()}
    }
    override fun close() {detector?.close();buffer?.recycle();buffer=null}
}
