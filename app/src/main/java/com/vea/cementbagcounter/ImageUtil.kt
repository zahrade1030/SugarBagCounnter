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
     * تبدیل ImageProxy به Bitmap
     * پشتیبانی از YUV_420_888، RowStride و PixelStride
     * مدیریت چرخش تصویر و آزادسازی حافظه
     */
    fun toBitmap(image: ImageProxy): Bitmap {

        val width = image.width
        val height = image.height

        require(width > 0 && height > 0) {
            "Invalid camera image dimensions"
        }

        require(image.format == ImageFormat.YUV_420_888) {
            "Unsupported image format: ${image.format}"
        }

        val planes = image.planes

        require(planes.size >= 3) {
            "Invalid YUV image planes"
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

        // کانال Y
        for (row in 0 until height) {

            val rowStart =
                yBuffer.position() + row * yPlane.rowStride

            for (col in 0 until width) {

                val index =
                    rowStart + col * yPlane.pixelStride

                if (index >= yBuffer.limit()) {
                    throw IllegalStateException(
                        "Y plane buffer overflow"
                    )
                }

                nv21[outputIndex++] = yBuffer.get(index)
            }
        }

        // کانال‌های V و U به ترتیب NV21
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

                if (
                    vIndex >= vBuffer.limit() ||
                    uIndex >= uBuffer.limit()
                ) {
                    throw IllegalStateException(
                        "UV plane buffer overflow"
                    )
                }

                nv21[outputIndex++] = vBuffer.get(vIndex)
                nv21[outputIndex++] = uBuffer.get(uIndex)
            }
        }

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val jpegOutput = ByteArrayOutputStream()

        val compressed = yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            90,
            jpegOutput
        )

        if (!compressed) {
            throw IllegalStateException(
                "YUV to JPEG conversion failed"
            )
        }

        val jpegBytes = jpegOutput.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size
        ) ?: throw IllegalStateException(
            "Bitmap decoding failed"
        )

        val rotation = image.imageInfo.rotationDegrees

        if (rotation == 0) {
            return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )

        if (rotated !== bitmap) {
            bitmap.recycle()
            bitmap = rotated
        }

        return bitmap
    }

    /**
     * تبدیل فریم دوربین به Bitmap با رزولوشن کوچک‌تر
     * برای کاهش مصرف حافظه و پردازش
     */
    fun toBitmapScaled(
        image: ImageProxy,
        maxDimension: Int = 1280
    ): Bitmap {

        val bitmap = toBitmap(image)

        if (maxDimension <= 0) {
            return bitmap
        }

        val largestDimension = max(
            bitmap.width,
            bitmap.height
        )

        if (largestDimension <= maxDimension) {
            return bitmap
        }

        val scale = maxDimension.toFloat() / largestDimension

        val newWidth = (bitmap.width * scale)
            .toInt()
            .coerceAtLeast(1)

        val newHeight = (bitmap.height * scale)
            .toInt()
            .coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            newWidth,
            newHeight,
            true
        )

        if (scaled !== bitmap) {
            bitmap.recycle()
        }

        return scaled
    }

    private fun max(a: Int, b: Int): Int {
        return if (a > b) a else b
    }
}
