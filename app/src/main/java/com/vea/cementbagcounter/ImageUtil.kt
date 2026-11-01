package com.vea.cementbagcounter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageUtil {

    fun toBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val nv21 = ByteArray(width * height * 3 / 2)

        var outputIndex = 0

        // کپی اطلاعات روشنایی تصویر
        for (row in 0 until height) {
            val rowStart =
                yBuffer.position() + row * yPlane.rowStride

            for (col in 0 until width) {
                nv21[outputIndex++] =
                    yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }

        // کپی اطلاعات رنگ تصویر
        for (row in 0 until height / 2) {
            val uRowStart =
                uBuffer.position() + row * uPlane.rowStride

            val vRowStart =
                vBuffer.position() + row * vPlane.rowStride

            for (col in 0 until width / 2) {
                nv21[outputIndex++] =
                    vBuffer.get(vRowStart + col * vPlane.pixelStride)

                nv21[outputIndex++] =
                    uBuffer.get(uRowStart + col * uPlane.pixelStride)
            }
        }

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val output = ByteArrayOutputStream()

        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            90,
            output
        )

        val bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(
                output.toByteArray(),
                0,
                output.size()
            )
        ) {
            "Camera image conversion failed"
        }

        val rotation = image.imageInfo.rotationDegrees

        if (rotation == 0) return bitmap

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
}
