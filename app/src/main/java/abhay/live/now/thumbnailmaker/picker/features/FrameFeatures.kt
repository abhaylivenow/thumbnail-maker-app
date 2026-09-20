package abhay.live.now.thumbnailmaker.picker.features

import abhay.live.now.thumbnailmaker.picker.Config
import abhay.live.now.thumbnailmaker.picker.FrameFeatureData
import abhay.live.now.thumbnailmaker.picker.ScanFrame

object FrameFeatures {

    private const val HISTOGRAM_BINS = 32

    fun compute(frame: ScanFrame): FrameFeatureData {
        val y = frame.yPlane
        val w = frame.width
        val h = frame.height

        val sharpness = laplacianVariance(y, w, h)
        val meanLuma = meanLuma(y)
        val clippedFraction = clippedFraction(y)
        val histogram = computeHistogram(y)

        return FrameFeatureData(
            index = frame.index,
            timestampUs = frame.timestampUs,
            sharpness = sharpness,
            meanLuma = meanLuma,
            clippedFraction = clippedFraction,
            histogram = histogram,
            yPlane = frame.yPlane,
            width = w,
            height = h,
            isKeyFrame = frame.isKeyFrame
        )
    }

    fun rejectFrames(
        frames: List<FrameFeatureData>,
        config: Config
    ): List<FrameFeatureData> {
        if (frames.size < 5) return frames

        // Sort by sharpness to find percentile threshold
        val sharpnessSorted = frames.map { it.sharpness }.sorted()
        val sharpnessThreshold = sharpnessSorted[
            (frames.size * config.sharpnessRejectPercentile).toInt().coerceAtMost(frames.size - 1)
        ]

        // Luma range rejection
        val lumaSorted = frames.map { it.meanLuma }.sorted()
        val lumaLow = lumaSorted[
            (frames.size * config.lumaRejectPercentile).toInt().coerceAtMost(frames.size - 1)
        ]
        val lumaHigh = lumaSorted[
            (frames.size * (1f - config.lumaRejectPercentile)).toInt().coerceAtMost(frames.size - 1)
        ]

        return frames.filter { f ->
            f.sharpness >= sharpnessThreshold && f.meanLuma in lumaLow..lumaHigh
        }
    }

    fun laplacianVariance(y: ByteArray, w: Int, h: Int): Float {
        // 3x3 Laplacian kernel: [0,1,0; 1,-4,1; 0,1,0]
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (row in 1 until h - 1) {
            for (col in 1 until w - 1) {
                val center = (y[row * w + col].toInt() and 0xFF) * -4
                val top = y[(row - 1) * w + col].toInt() and 0xFF
                val bottom = y[(row + 1) * w + col].toInt() and 0xFF
                val left = y[row * w + (col - 1)].toInt() and 0xFF
                val right = y[row * w + (col + 1)].toInt() and 0xFF

                val lap = center + top + bottom + left + right
                sum += lap
                sumSq += lap.toLong() * lap
                count++
            }
        }

        if (count == 0) return 0f
        val mean = sum / count
        return ((sumSq / count) - mean * mean).toFloat()
    }

    fun meanLuma(y: ByteArray): Float {
        var sum = 0L
        for (b in y) {
            sum += (b.toInt() and 0xFF)
        }
        return sum.toFloat() / y.size
    }

    fun clippedFraction(y: ByteArray): Float {
        var clipped = 0
        for (b in y) {
            val v = b.toInt() and 0xFF
            if (v == 0 || v == 255) clipped++
        }
        return clipped.toFloat() / y.size
    }

    fun computeHistogram(y: ByteArray): FloatArray {
        val hist = FloatArray(HISTOGRAM_BINS)
        val binScale = HISTOGRAM_BINS / 256f
        for (b in y) {
            val v = b.toInt() and 0xFF
            val bin = (v * binScale).toInt().coerceAtMost(HISTOGRAM_BINS - 1)
            hist[bin]++
        }
        // Normalise
        val total = y.size.toFloat()
        for (i in hist.indices) {
            hist[i] /= total
        }
        return hist
    }

    fun sobelEdgeDensity(y: ByteArray, w: Int, h: Int): Float {
        var edgeCount = 0
        val threshold = 50 // edge threshold on gradient magnitude
        var count = 0

        for (row in 1 until h - 1) {
            for (col in 1 until w - 1) {
                val tl = y[(row - 1) * w + (col - 1)].toInt() and 0xFF
                val tc = y[(row - 1) * w + col].toInt() and 0xFF
                val tr = y[(row - 1) * w + (col + 1)].toInt() and 0xFF
                val ml = y[row * w + (col - 1)].toInt() and 0xFF
                val mr = y[row * w + (col + 1)].toInt() and 0xFF
                val bl = y[(row + 1) * w + (col - 1)].toInt() and 0xFF
                val bc = y[(row + 1) * w + col].toInt() and 0xFF
                val br = y[(row + 1) * w + (col + 1)].toInt() and 0xFF

                val gx = -tl + tr - 2 * ml + 2 * mr - bl + br
                val gy = -tl - 2 * tc - tr + bl + 2 * bc + br

                val mag = kotlin.math.sqrt((gx * gx + gy * gy).toFloat())
                if (mag > threshold) edgeCount++
                count++
            }
        }

        return if (count > 0) edgeCount.toFloat() / count else 0f
    }
}
