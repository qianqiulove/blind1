package com.blind.v1

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageProxyUtils {
    fun toJpegBytes(image: ImageProxy, quality: Int = 70): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        return if (yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)) {
            out.toByteArray()
        } else {
            null
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val nv21 = ByteArray(width * height * 3 / 2)
        var outOffset = 0

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yData = ByteArray(yBuffer.remaining())
        yBuffer.get(yData)
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                nv21[outOffset++] = yData[rowStart + col * yPixelStride]
            }
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uData = ByteArray(uBuffer.remaining())
        val vData = ByteArray(vBuffer.remaining())
        uBuffer.get(uData)
        vBuffer.get(vData)

        for (row in 0 until height / 2) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until width / 2) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                nv21[outOffset++] = vData[vIndex] // NV21 expects V then U
                nv21[outOffset++] = uData[uIndex]
            }
        }
        return nv21
    }
}
