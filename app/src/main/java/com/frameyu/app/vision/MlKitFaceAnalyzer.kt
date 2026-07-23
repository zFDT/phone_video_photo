package com.frameyu.app.vision

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/** ML Kit Face Detection 适配器，不把 ML Kit 类型泄漏到分析结果模型。 */
class MlKitFaceAnalyzer(
    options: FaceDetectorOptions = defaultOptions(),
) : FaceAnalyzer {
    private val detector: FaceDetector = FaceDetection.getClient(options)
    private val closed = AtomicBoolean(false)

    override fun analyze(input: FaceInput): Task<List<FaceObservation>> {
        check(!closed.get()) { "人脸分析器已经释放" }
        return detector.process(input.toInputImage()).continueWith { task ->
            task.result.map(::mapMlKitFace)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            detector.close()
        }
    }

    private fun FaceInput.toInputImage(): InputImage = when (this) {
        is FaceInput.BitmapInput -> InputImage.fromBitmap(bitmap, rotationDegrees)
        is FaceInput.MediaImageInput -> InputImage.fromMediaImage(image, rotationDegrees)
    }

    companion object {
        fun defaultOptions(): FaceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
    }
}

private fun mapMlKitFace(face: Face): FaceObservation = FaceObservationMapper.map(
    RawFaceDetection(
        bounds = FaceBounds(
            left = face.boundingBox.left,
            top = face.boundingBox.top,
            right = face.boundingBox.right,
            bottom = face.boundingBox.bottom,
        ),
        headEulerAngleX = face.headEulerAngleX,
        headEulerAngleY = face.headEulerAngleY,
        headEulerAngleZ = face.headEulerAngleZ,
        leftEyeOpenProbability = face.leftEyeOpenProbability,
        rightEyeOpenProbability = face.rightEyeOpenProbability,
        smilingProbability = face.smilingProbability,
        trackingId = face.trackingId,
    ),
)
