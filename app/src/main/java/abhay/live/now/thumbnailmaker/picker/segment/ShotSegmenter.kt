package abhay.live.now.thumbnailmaker.picker.segment

import abhay.live.now.thumbnailmaker.picker.Config
import abhay.live.now.thumbnailmaker.picker.FrameFeatureData
import abhay.live.now.thumbnailmaker.picker.Shot
import kotlin.math.sqrt

object ShotSegmenter {

    fun segment(frames: List<FrameFeatureData>, config: Config): List<Shot> {
        if (frames.size < 2) {
            return listOf(
                Shot(
                    index = 0,
                    startUs = frames.firstOrNull()?.timestampUs ?: 0,
                    endUs = frames.lastOrNull()?.timestampUs ?: 0,
                    frameIndices = frames.map { it.index }
                )
            )
        }

        // Compute chi-square distances between consecutive histograms
        val distances = FloatArray(frames.size - 1) { i ->
            chiSquareDistance(frames[i].histogram, frames[i + 1].histogram)
        }

        // Compute mean and stddev of distances
        val mean = distances.average().toFloat()
        val variance = distances.map { (it - mean) * (it - mean) }.average().toFloat()
        val stddev = sqrt(variance)
        val threshold = mean + config.shotCutSigmaMultiplier * stddev

        // Find cuts: local maxima above threshold
        val cutIndices = mutableListOf<Int>()
        for (i in distances.indices) {
            if (distances[i] > threshold) {
                val isLocalMax = (i == 0 || distances[i] >= distances[i - 1]) &&
                    (i == distances.size - 1 || distances[i] >= distances[i + 1])

                // Boost confidence if the next frame is a keyframe
                val nextFrameIsKey = frames.getOrNull(i + 1)?.isKeyFrame == true

                if (isLocalMax || (distances[i] > threshold * 1.5f) || nextFrameIsKey) {
                    cutIndices.add(i + 1) // cut happens BEFORE frame i+1
                }
            }
        }

        // Build shots from cut points
        val shots = mutableListOf<Shot>()
        var startIdx = 0

        for ((shotIndex, cutIdx) in cutIndices.withIndex()) {
            val shotFrames = frames.subList(startIdx, cutIdx)
            if (shotFrames.isNotEmpty()) {
                shots.add(
                    Shot(
                        index = shotIndex,
                        startUs = shotFrames.first().timestampUs,
                        endUs = shotFrames.last().timestampUs,
                        frameIndices = shotFrames.map { it.index }
                    )
                )
            }
            startIdx = cutIdx
        }

        // Add final shot
        if (startIdx < frames.size) {
            val shotFrames = frames.subList(startIdx, frames.size)
            if (shotFrames.isNotEmpty()) {
                shots.add(
                    Shot(
                        index = shots.size,
                        startUs = shotFrames.first().timestampUs,
                        endUs = shotFrames.last().timestampUs,
                        frameIndices = shotFrames.map { it.index }
                    )
                )
            }
        }

        return shots
    }

    fun chiSquareDistance(h1: FloatArray, h2: FloatArray): Float {
        var dist = 0f
        for (i in h1.indices) {
            val sum = h1[i] + h2[i]
            if (sum > 0f) {
                val diff = h1[i] - h2[i]
                dist += diff * diff / sum
            }
        }
        return dist
    }
}
