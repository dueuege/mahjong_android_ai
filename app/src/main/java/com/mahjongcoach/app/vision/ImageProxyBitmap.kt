package com.mahjongcoach.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Decode a YUV_420_888 [ImageProxy] (what CameraX `ImageAnalysis` hands us)
 * into an upright RGB bitmap. Handles the analyzer's `rotationDegrees` hint so
 * the model sees tiles in the same orientation the user holds the phone.
 *
 * Shared between [LlmHandRecognizer] (Bitmap → base64 → LLM) and
 * [OnnxHandRecognizer] (Bitmap → float tensor → ORT). The proxy is NOT closed
 * here — callers manage that lifecycle themselves.
 */
fun ImageProxy.toRgbBitmap(): Bitmap? {
    val yPlane = planes[0].buffer
    val uPlane = planes[1].buffer
    val vPlane = planes[2].buffer
    val ySize = yPlane.remaining()
    val uSize = uPlane.remaining()
    val vSize = vPlane.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yPlane.get(nv21, 0, ySize)
    vPlane.get(nv21, ySize, vSize)
    uPlane.get(nv21, ySize + vSize, uSize)

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val jpeg = ByteArrayOutputStream().use { out ->
        yuv.compressToJpeg(Rect(0, 0, width, height), 80, out); out.toByteArray()
    }
    val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}
