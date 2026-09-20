package abhay.live.now.thumbnailmaker.picker.selection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import abhay.live.now.thumbnailmaker.BuildConfig
import abhay.live.now.thumbnailmaker.picker.CandidateFrame
import abhay.live.now.thumbnailmaker.picker.Config
import abhay.live.now.thumbnailmaker.picker.Shot
import abhay.live.now.thumbnailmaker.picker.features.FrameFeatures
import abhay.live.now.thumbnailmaker.picker.scoring.FrameScorer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlin.math.abs
import kotlin.math.sqrt

class MmrSelector(private val context: Context, private val config: Config) {

    fun select(
        candidates: List<CandidateFrame>,
        shots: List<Shot>,
        totalDurationUs: Long
    ): List<CandidateFrame> {
        if (candidates.isEmpty()) return emptyList()
        if (candidates.size <= config.targetOutputCount) return candidates

        // Compute embeddings
        val embeddingsOk = if (config.useEmbeddings) {
            computeEmbeddings(candidates)
        } else {
            false
        }

        // Build similarity function
        val similarity: (CandidateFrame, CandidateFrame) -> Float = if (embeddingsOk) {
            { a, b -> cosineSimilarity(a.embedding!!, b.embedding!!) }
        } else {
            { a, b -> histogramCosineSimilarity(a, b) }
        }

        // Identify significant shots (> 10% of total duration)
        val significantShots = shots.filter { shot ->
            val shotDuration = shot.endUs - shot.startUs
            shotDuration.toFloat() / totalDurationUs > config.significantShotFraction
        }.map { it.index }.toSet()

        // MMR selection
        val selected = mutableListOf<CandidateFrame>()
        val remaining = candidates.toMutableList()

        // Greedy: pick the highest scoring candidate first
        val first = remaining.maxByOrNull { it.score } ?: return emptyList()
        selected.add(first)
        remaining.remove(first)

        val minGapUs = config.minTemporalGapMs * 1000

        while (selected.size < config.targetOutputCount && remaining.isNotEmpty()) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestCandidate: CandidateFrame? = null

            for (candidate in remaining) {
                // Temporal gap constraint
                val tooClose = selected.any { sel ->
                    abs(sel.timestampUs - candidate.timestampUs) < minGapUs
                }
                if (tooClose) continue

                // MMR score: λ * quality - (1-λ) * maxSimilarity
                val maxSim = selected.maxOf { sel -> similarity(candidate, sel) }
                val mmrScore = config.mmmLambda * candidate.score -
                    (1f - config.mmmLambda) * maxSim

                if (mmrScore > bestScore) {
                    bestScore = mmrScore
                    bestCandidate = candidate
                }
            }

            if (bestCandidate == null) {
                // Relax temporal constraint if we can't find any
                val relaxed = remaining.maxByOrNull { c ->
                    val maxSim = selected.maxOfOrNull { s -> similarity(c, s) } ?: 0f
                    config.mmmLambda * c.score - (1f - config.mmmLambda) * maxSim
                }
                if (relaxed != null) {
                    selected.add(relaxed)
                    remaining.remove(relaxed)
                } else {
                    break
                }
            } else {
                selected.add(bestCandidate)
                remaining.remove(bestCandidate)
            }
        }

        // Ensure significant shot coverage
        val coveredShots = selected.map { it.shotIndex }.toSet()
        val missingSignificant = significantShots - coveredShots
        for (shotIdx in missingSignificant) {
            if (selected.size >= config.targetOutputCount) {
                // Replace lowest scoring selected frame (that isn't the only rep of its shot)
                val replaceable = selected.filter { s ->
                    selected.count { it.shotIndex == s.shotIndex } > 1
                }.minByOrNull { it.score }

                val replacement = remaining.filter { it.shotIndex == shotIdx }
                    .maxByOrNull { it.score }

                if (replaceable != null && replacement != null) {
                    selected.remove(replaceable)
                    remaining.add(replaceable)
                    selected.add(replacement)
                    remaining.remove(replacement)
                }
            } else {
                val rep = remaining.filter { it.shotIndex == shotIdx }
                    .maxByOrNull { it.score }
                if (rep != null) {
                    selected.add(rep)
                    remaining.remove(rep)
                }
            }
        }

        return selected.sortedBy { it.timestampUs }
    }

    private fun computeEmbeddings(candidates: List<CandidateFrame>): Boolean {
        // Guard: verify model file exists in assets before calling MediaPipe native code,
        // which will SIGSEGV (not throw) if the file is missing.
        val modelPath = "mobilenet_v3_small.tflite"
        val modelExists = try {
            context.assets.open(modelPath).use { true }
        } catch (_: Exception) {
            false
        }
        if (!modelExists) {
            if (BuildConfig.DEBUG) Log.w("MmrSelector", "Model asset '$modelPath' not found, skipping embeddings")
            return false
        }

        return try {
            val options = ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelPath)
                        .build()
                )
                .setQuantize(true)
                .build()

            val embedder = ImageEmbedder.createFromOptions(context, options)

            for (c in candidates) {
                val bitmap = FrameScorer.yPlaneToBitmap(c.yPlane, c.width, c.height)
                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = embedder.embed(mpImage)
                if (result.embeddingResult().embeddings().isNotEmpty()) {
                    val emb = result.embeddingResult().embeddings()[0]
                    val floatList = emb.floatEmbedding()
                    val quantList = emb.quantizedEmbedding()
                    c.embedding = when {
                        floatList.isNotEmpty() -> FloatArray(floatList.size) { floatList[it] }
                        quantList.isNotEmpty() -> FloatArray(quantList.size) {
                            (quantList[it].toInt() and 0xFF).toFloat() / 255f
                        }
                        else -> null
                    }
                }
                bitmap.recycle()
            }

            embedder.close()
            candidates.all { it.embedding != null }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("MmrSelector", "Embedding computation failed", e)
            false
        }
    }

    private fun histogramCosineSimilarity(a: CandidateFrame, b: CandidateFrame): Float {
        val histA = FrameFeatures.computeHistogram(a.yPlane)
        val histB = FrameFeatures.computeHistogram(b.yPlane)
        return cosineSimilarity(histA, histB)
    }

    companion object {
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom > 0f) dot / denom else 0f
        }
    }
}
