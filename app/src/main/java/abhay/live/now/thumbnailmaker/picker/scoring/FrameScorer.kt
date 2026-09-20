package abhay.live.now.thumbnailmaker.picker.scoring

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import abhay.live.now.thumbnailmaker.BuildConfig
import abhay.live.now.thumbnailmaker.picker.AudioPeak
import abhay.live.now.thumbnailmaker.picker.CandidateFrame
import abhay.live.now.thumbnailmaker.picker.Config
import abhay.live.now.thumbnailmaker.picker.Shot
import abhay.live.now.thumbnailmaker.picker.features.FrameFeatures
import abhay.live.now.thumbnailmaker.picker.features.ScoringWeights
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

class FrameScorer(private val config: Config) {

    suspend fun score(
        candidates: List<CandidateFrame>,
        shots: List<Shot>,
        audioPeaks: List<AudioPeak>,
        audioAvailable: Boolean
    ) {
        if (candidates.isEmpty()) return

        // Detect faces on candidate bitmaps
        val faceDetectionOk = detectFaces(candidates)

        // Compute edge density
        for (c in candidates) {
            c.edgeDensity = FrameFeatures.sobelEdgeDensity(c.yPlane, c.width, c.height)
        }

        // Compute audio proximity for each candidate
        if (audioAvailable && audioPeaks.isNotEmpty()) {
            for (c in candidates) {
                val minDist = audioPeaks.minOf { abs(it.timestampUs - c.timestampUs) }
                // Convert to a score: closer to peak = higher score
                // Max proximity window: 2 seconds
                val maxDistUs = 2_000_000L
                c.audioProximity = (1f - (minDist.toFloat() / maxDistUs)).coerceIn(0f, 1f)
            }
        }

        // Compute mid-shot score
        val shotMap = shots.associateBy { it.index }
        for (c in candidates) {
            val shot = shotMap[c.shotIndex]
            if (shot != null && shot.endUs > shot.startUs) {
                val shotDuration = shot.endUs - shot.startUs
                val posInShot = c.timestampUs - shot.startUs
                val relPos = posInShot.toFloat() / shotDuration
                // Peak at 0.5 (middle), using 1 - 2*|pos - 0.5|
                c.midShotScore = (1f - 2f * abs(relPos - 0.5f)).coerceIn(0f, 1f)
            }
        }

        // Normalise all scores to [0,1]
        val normSharpness = ScoringWeights.normalise(candidates.map { it.sharpness })
        val normFace = ScoringWeights.normalise(candidates.map { it.faceCount.toFloat() })
        val normAudio = ScoringWeights.normalise(candidates.map { it.audioProximity })
        val normMidShot = ScoringWeights.normalise(candidates.map { it.midShotScore })
        val normEdge = ScoringWeights.normalise(candidates.map { it.edgeDensity })

        // Get weights with appropriate fallbacks
        val weights = config.weights.renormalise(
            dropFace = !faceDetectionOk,
            dropAudio = !audioAvailable || audioPeaks.isEmpty()
        )

        // Compute weighted score
        for (i in candidates.indices) {
            candidates[i].score =
                weights.sharpness * normSharpness[i] +
                weights.face * normFace[i] +
                weights.audio * normAudio[i] +
                weights.midShot * normMidShot[i] +
                weights.edge * normEdge[i]
        }
    }

    private suspend fun detectFaces(candidates: List<CandidateFrame>): Boolean {
        return try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            val detector = FaceDetection.getClient(options)

            for (c in candidates) {
                val bitmap = yPlaneToBitmap(c.yPlane, c.width, c.height)
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val faces = detector.process(inputImage).await()
                c.faceCount = faces.size
                bitmap.recycle()
            }

            detector.close()
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("FrameScorer", "Face detection failed", e)
            false
        }
    }

    companion object {
        fun yPlaneToBitmap(yPlane: ByteArray, width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            for (i in yPlane.indices) {
                val y = yPlane[i].toInt() and 0xFF
                pixels[i] = Color.rgb(y, y, y)
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        }
    }
}
