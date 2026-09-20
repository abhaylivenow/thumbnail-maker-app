package abhay.live.now.thumbnailmaker.picker

import android.graphics.Bitmap

data class ScanFrame(
    val index: Int,
    val timestampUs: Long,
    val yPlane: ByteArray,
    val width: Int,
    val height: Int,
    val isKeyFrame: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanFrame) return false
        return index == other.index && timestampUs == other.timestampUs
    }

    override fun hashCode(): Int = 31 * index + timestampUs.hashCode()
}

data class FrameFeatureData(
    val index: Int,
    val timestampUs: Long,
    val sharpness: Float,
    val meanLuma: Float,
    val clippedFraction: Float,
    val histogram: FloatArray,
    val yPlane: ByteArray,
    val width: Int,
    val height: Int,
    val isKeyFrame: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameFeatureData) return false
        return index == other.index && timestampUs == other.timestampUs
    }

    override fun hashCode(): Int = 31 * index + timestampUs.hashCode()
}

data class Shot(
    val index: Int,
    val startUs: Long,
    val endUs: Long,
    val frameIndices: List<Int>
)

data class CandidateFrame(
    val frameIndex: Int,
    val timestampUs: Long,
    val shotIndex: Int,
    val sharpness: Float,
    val yPlane: ByteArray,
    val width: Int,
    val height: Int,
    var score: Float = 0f,
    var faceCount: Int = 0,
    var edgeDensity: Float = 0f,
    var audioProximity: Float = 0f,
    var midShotScore: Float = 0f,
    var embedding: FloatArray? = null,
    var fullResBitmap: Bitmap? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CandidateFrame) return false
        return frameIndex == other.frameIndex && timestampUs == other.timestampUs
    }

    override fun hashCode(): Int = 31 * frameIndex + timestampUs.hashCode()
}

data class AudioPeak(
    val timestampUs: Long,
    val rms: Float
)

sealed class PickerEvent {
    data class StageUpdate(val stage: String, val progress: Float) : PickerEvent()
    data class Candidate(val frame: CandidateFrame) : PickerEvent()
    data class Complete(val frames: List<CandidateFrame>) : PickerEvent()
    data class Error(val message: String, val cause: Throwable? = null) : PickerEvent()
}
