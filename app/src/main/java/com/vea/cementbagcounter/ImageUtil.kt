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

    /**
     * تبدیل فریم دوربین به Bitmap
     * پشتیبانی از YUV_420_888 و Stride
     */
    fun toBitmap(image: ImageProxy): Bitmap {

        val width = image.width
        val height = image.height

        require(width > 0 && height > 0) {
            "Invalid camera image dimensions"
        }

        val planes = image.planes

        require(planes.size >= 3) {
            "Unsupported camera image format"
        }

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val ySize = width * height
        val uvSize = width * height / 2

        val nv21 = ByteArray(ySize + uvSize)

        var outputIndex = 0

        // کپی کانال Y
        for (row in 0 until height) {

            val rowStart =
                yBuffer.position() + row * yPlane.rowStride

            for (col in 0 until width) {

                val index =
                    rowStart + col * yPlane.pixelStride

                nv21[outputIndex++] = yBuffer.get(index)
            }
        }

        // کپی کانال‌های V و U برای فرمت NV21
        for (row in 0 until height / 2) {

            val uRowStart =
                uBuffer.position() + row * uPlane.rowStride

            val vRowStart =
                vBuffer.position() + row * vPlane.rowStride

            for (col in 0 until width / 2) {

                val vIndex =
                    vRowStart + col * vPlane.pixelStride

                val uIndex =
                    uRowStart + col * uPlane.pixelStride

                nv21[outputIndex++] = vBuffer.get(vIndex)
                nv21[outputIndex++] = uBuffer.get(uIndex)
            }
        }

        // تبدیل YUV به JPEG
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val output = ByteArrayOutputStream()

        val success = yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            85,
            output
        )

        if (!success) {
            throw IllegalStateException(
                "YUV to JPEG conversion failed"
            )
        }

        val jpegBytes = output.toByteArray()

        // تبدیل JPEG به Bitmap
        val bitmap = BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size
        ) ?: throw IllegalStateException(
            "Camera image conversion failed"
        )

        // چرخش تصویر براساس اطلاعات CameraX
        val rotation = image.imageInfo.rotationDegrees

        if (rotation == 0) {
            return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )

        if (rotatedBitmap !== bitmap) {
            bitmap.recycle()
        }

        return rotatedBitmap
    }
}
