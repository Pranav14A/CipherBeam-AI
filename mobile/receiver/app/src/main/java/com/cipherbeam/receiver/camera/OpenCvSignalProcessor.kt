package com.cipherbeam.receiver.camera

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 15A:
 *
 * OpenCV-based camera-frame processing.
 *
 * This class intentionally does NOT replace the current optical decoder.
 *
 * Responsibilities:
 * - Read CameraX YUV_420_888 planes.
 * - Build only the localized ROI in I420 format.
 * - Convert only that ROI to RGB with OpenCV.
 * - Calculate RED/GREEN dominance metrics from the ROI.
 *
 * The current RedLedAnalyzer / OpticalDecoder pipeline remains the
 * authoritative decoding path during Phase 15A.
 */
class OpenCvSignalProcessor {

    data class Result(
        val valid: Boolean,
        val meanRed: Double,
        val meanGreen: Double,
        val meanBlue: Double,
        val redDominance: Double,
        val greenDominance: Double,
        val roiLeft: Int,
        val roiTop: Int,
        val roiWidth: Int,
        val roiHeight: Int
    )

    companion object {

        /*
         * Keep the OpenCV ROI at approximately the same scale as the
         * current analyzer's 8% × 8% ROI.
         *
         * I420 requires even ROI dimensions and even ROI origin.
         */
        private const val ROI_FRACTION = 0.08

        private const val CHANNEL_MAX = 255.0
    }

    private var yuvMat: Mat? = null
    private var rgbMat: Mat? = null

    /**
     * Process one CameraX YUV_420_888 frame.
     *
     * No packet decoding occurs here.
     *
     * IMPORTANT:
     * The existing RED center supplied by RedLedAnalyzer is authoritative.
     * This class does not perform its own LED search.
     */
    fun process(
        image: ImageProxy,
        centerX: Int,
        centerY: Int
    ): Result {

        val width = image.width
        val height = image.height

        if (
            width <= 0 ||
            height <= 0 ||
            width % 2 != 0 ||
            height % 2 != 0
        ) {
            return invalidResult()
        }

        val yPlane =
            image.planes.getOrNull(0)
                ?: return invalidResult()

        val uPlane =
            image.planes.getOrNull(1)
                ?: return invalidResult()

        val vPlane =
            image.planes.getOrNull(2)
                ?: return invalidResult()

        /*
         * The ROI is calculated before reading the planes.
         *
         * This allows Test B to avoid creating full-frame Y/U/V
         * intermediate ByteArrays.
         */
        val roi =
            createRoi(
                width = width,
                height = height,
                centerX = centerX,
                centerY = centerY
            )

        /*
         * I420 chroma is sampled at half resolution.
         */
        val roiChromaWidth =
            roi.width / 2

        val roiChromaHeight =
            roi.height / 2

        val roiYSize =
            roi.width * roi.height

        val roiChromaSize =
            roiChromaWidth * roiChromaHeight

        /*
         * This is now the ONLY YUV byte array created for the frame.
         *
         * It contains:
         *
         * Y
         * U
         * V
         *
         * for the localized ROI only.
         */
        val roiI420 =
            ByteArray(
                roiYSize +
                        roiChromaSize +
                        roiChromaSize
            )

        var destinationOffset = 0

        /*
         * Copy ONLY the localized Y ROI.
         */
        val copiedY =
            copyPlaneRoi(
                plane = yPlane,
                sourceLeft = roi.x,
                sourceTop = roi.y,
                width = roi.width,
                height = roi.height,
                destination = roiI420,
                destinationOffset = destinationOffset
            )

        if (!copiedY) {
            return invalidResult()
        }

        destinationOffset +=
            roiYSize

        /*
         * U/V planes are half resolution.
         */
        val chromaLeft =
            roi.x / 2

        val chromaTop =
            roi.y / 2

        /*
         * Copy ONLY the localized U ROI.
         */
        val copiedU =
            copyPlaneRoi(
                plane = uPlane,
                sourceLeft = chromaLeft,
                sourceTop = chromaTop,
                width = roiChromaWidth,
                height = roiChromaHeight,
                destination = roiI420,
                destinationOffset = destinationOffset
            )

        if (!copiedU) {
            return invalidResult()
        }

        destinationOffset +=
            roiChromaSize

        /*
         * Copy ONLY the localized V ROI.
         */
        val copiedV =
            copyPlaneRoi(
                plane = vPlane,
                sourceLeft = chromaLeft,
                sourceTop = chromaTop,
                width = roiChromaWidth,
                height = roiChromaHeight,
                destination = roiI420,
                destinationOffset = destinationOffset
            )

        if (!copiedV) {
            return invalidResult()
        }

        /*
         * OpenCV now receives ONLY the localized ROI.
         *
         * No full-frame YUV Mat is created.
         */
        val yuv =
            getYuvMat(
                rows = roi.height + roi.height / 2,
                columns = roi.width
            )

        yuv.put(
            0,
            0,
            roiI420
        )

        val rgb =
            getRgbMat(
                rows = roi.height,
                columns = roi.width
            )

        /*
         * I420 → RGB.
         *
         * The input Mat contains only the localized ROI.
         */
        Imgproc.cvtColor(
            yuv,
            rgb,
            Imgproc.COLOR_YUV2RGB_I420
        )

        return try {

            val mean =
                Core.mean(rgb)

            val meanRed =
                mean.`val`[0]

            val meanGreen =
                mean.`val`[1]

            val meanBlue =
                mean.`val`[2]

            /*
             * Dominance metrics intentionally remain simple in Phase 15A.
             *
             * They are diagnostics first.
             * We will not use arbitrary OpenCV thresholds to control the
             * decoder until real measurements have been collected.
             */
            val redDominance =
                (
                        meanRed -
                                (
                                        meanGreen +
                                                meanBlue
                                        ) / 2.0
                        ) /
                        CHANNEL_MAX

            val greenDominance =
                (
                        meanGreen -
                                (
                                        meanRed +
                                                meanBlue
                                        ) / 2.0
                        ) /
                        CHANNEL_MAX

            Result(
                valid = true,
                meanRed = meanRed,
                meanGreen = meanGreen,
                meanBlue = meanBlue,
                redDominance = redDominance,
                greenDominance = greenDominance,
                roiLeft = roi.x,
                roiTop = roi.y,
                roiWidth = roi.width,
                roiHeight = roi.height
            )

        } catch (_: Exception) {

            invalidResult()
        }
    }

    /**
     * Create an even-aligned ROI suitable for I420.
     *
     * I420 uses 2×2 chroma subsampling, so the ROI origin and dimensions
     * must be compatible with the chroma planes.
     */
    private fun createRoi(
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int
    ): Rect {

        val roiWidth =
            max(
                2,
                ((width * ROI_FRACTION).toInt() / 2) * 2
            )

        val roiHeight =
            max(
                2,
                ((height * ROI_FRACTION).toInt() / 2) * 2
            )

        val halfWidth =
            roiWidth / 2

        val halfHeight =
            roiHeight / 2

        val safeCenterX =
            centerX.coerceIn(
                0,
                width - 1
            )

        val safeCenterY =
            centerY.coerceIn(
                0,
                height - 1
            )

        var left =
            safeCenterX - halfWidth

        var top =
            safeCenterY - halfHeight

        left =
            max(
                0,
                min(
                    width - roiWidth,
                    left
                )
            )

        top =
            max(
                0,
                min(
                    height - roiHeight,
                    top
                )
            )

        /*
         * Align ROI origin to the 2×2 chroma grid.
         */
        left =
            left and 1.inv()

        top =
            top and 1.inv()

        return Rect(
            left,
            top,
            roiWidth,
            roiHeight
        )
    }

    /**
     * Copy only a rectangular region from one YUV plane.
     *
     * This respects Android's:
     * - rowStride
     * - pixelStride
     *
     * No full-plane ByteArray is created.
     */
    private fun copyPlaneRoi(
        plane: ImageProxy.PlaneProxy,
        sourceLeft: Int,
        sourceTop: Int,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationOffset: Int
    ): Boolean {

        if (
            sourceLeft < 0 ||
            sourceTop < 0 ||
            width <= 0 ||
            height <= 0 ||
            destinationOffset < 0 ||
            destinationOffset + width * height > destination.size
        ) {
            return false
        }

        val buffer =
            plane.buffer.duplicate()

        val rowStride =
            plane.rowStride

        val pixelStride =
            plane.pixelStride

        if (
            rowStride <= 0 ||
            pixelStride <= 0
        ) {
            return false
        }

        var outputIndex =
            destinationOffset

        for (row in 0 until height) {

            val sourceRow =
                sourceTop + row

            val rowStart =
                sourceRow * rowStride

            for (column in 0 until width) {

                val sourceColumn =
                    sourceLeft + column

                val bufferIndex =
                    rowStart +
                            sourceColumn * pixelStride

                if (
                    bufferIndex < 0 ||
                    bufferIndex >= buffer.limit()
                ) {
                    return false
                }

                destination[outputIndex++] =
                    buffer.get(bufferIndex)
            }
        }

        return true
    }

    private fun getYuvMat(
        rows: Int,
        columns: Int
    ): Mat {

        val current =
            yuvMat

        if (
            current == null ||
            current.rows() != rows ||
            current.cols() != columns
        ) {
            current?.release()

            yuvMat =
                Mat(
                    rows,
                    columns,
                    CvType.CV_8UC1
                )
        }

        return yuvMat!!
    }

    private fun getRgbMat(
        rows: Int,
        columns: Int
    ): Mat {

        val current =
            rgbMat

        if (
            current == null ||
            current.rows() != rows ||
            current.cols() != columns
        ) {
            current?.release()

            rgbMat =
                Mat(
                    rows,
                    columns,
                    CvType.CV_8UC3
                )
        }

        return rgbMat!!
    }

    fun release() {

        yuvMat?.release()
        rgbMat?.release()

        yuvMat = null
        rgbMat = null
    }

    private fun invalidResult(): Result =
        Result(
            valid = false,
            meanRed = 0.0,
            meanGreen = 0.0,
            meanBlue = 0.0,
            redDominance = 0.0,
            greenDominance = 0.0,
            roiLeft = 0,
            roiTop = 0,
            roiWidth = 0,
            roiHeight = 0
        )
}