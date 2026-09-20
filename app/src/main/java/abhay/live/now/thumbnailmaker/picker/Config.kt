package abhay.live.now.thumbnailmaker.picker

data class Config(
    val scanFps: Double = 2.0,
    val scanEdgePx: Int = 320,
    val candidatesPerShot: Int = 3,
    val shotBoundaryMarginMs: Long = 150,
    val longShotThresholdMs: Long = 4000,
    val targetCandidateCount: IntRange = 20..40,
    val targetOutputCount: Int = 10,
    val minTemporalGapMs: Long = 1000,
    val audioPeakGapMs: Long = 500,
    val audioWindowSamples: Int = 400,
    val audioHopSamples: Int = 160,
    val audioSampleRate: Int = 16000,
    val sharpnessRejectPercentile: Float = 0.15f,
    val lumaRejectPercentile: Float = 0.05f,
    val shotCutSigmaMultiplier: Float = 2.5f,
    val mmmLambda: Float = 0.7f,
    val useEmbeddings: Boolean = false,
    val significantShotFraction: Float = 0.10f,
    val weights: Weights = Weights()
)

data class Weights(
    val sharpness: Float = 0.35f,
    val face: Float = 0.25f,
    val audio: Float = 0.20f,
    val midShot: Float = 0.10f,
    val edge: Float = 0.10f
) {
    fun renormalise(dropFace: Boolean = false, dropAudio: Boolean = false): Weights {
        var s = sharpness
        var f = if (dropFace) 0f else face
        var a = if (dropAudio) 0f else audio
        var m = midShot
        var e = edge
        val total = s + f + a + m + e
        if (total <= 0f) return Weights(0.2f, 0.2f, 0.2f, 0.2f, 0.2f)
        s /= total; f /= total; a /= total; m /= total; e /= total
        return Weights(s, f, a, m, e)
    }
}
