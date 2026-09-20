package abhay.live.now.thumbnailmaker.picker.pool

import abhay.live.now.thumbnailmaker.picker.CandidateFrame
import abhay.live.now.thumbnailmaker.picker.Config
import abhay.live.now.thumbnailmaker.picker.FrameFeatureData
import abhay.live.now.thumbnailmaker.picker.Shot

object CandidatePool {

    fun selectCandidates(
        shots: List<Shot>,
        features: Map<Int, FrameFeatureData>,
        config: Config
    ): List<CandidateFrame> {
        val candidates = mutableListOf<CandidateFrame>()

        for (shot in shots) {
            val shotDurationUs = shot.endUs - shot.startUs
            val boundaryMarginUs = config.shotBoundaryMarginMs * 1000

            // Get frames for this shot, excluding boundary frames
            val validFrames = shot.frameIndices
                .mapNotNull { features[it] }
                .filter { frame ->
                    val distFromStart = frame.timestampUs - shot.startUs
                    val distFromEnd = shot.endUs - frame.timestampUs
                    distFromStart >= boundaryMarginUs && distFromEnd >= boundaryMarginUs
                }
                .ifEmpty {
                    // If all frames are boundary frames, use all frames
                    shot.frameIndices.mapNotNull { features[it] }
                }

            // Split long shots and sample each half
            val subGroups = if (shotDurationUs > config.longShotThresholdMs * 1000) {
                val midUs = shot.startUs + shotDurationUs / 2
                val firstHalf = validFrames.filter { it.timestampUs <= midUs }
                val secondHalf = validFrames.filter { it.timestampUs > midUs }
                listOfNotNull(
                    firstHalf.ifEmpty { null },
                    secondHalf.ifEmpty { null }
                )
            } else {
                listOf(validFrames)
            }

            for (group in subGroups) {
                val topN = group
                    .sortedByDescending { it.sharpness }
                    .take(config.candidatesPerShot)

                for (frame in topN) {
                    candidates.add(
                        CandidateFrame(
                            frameIndex = frame.index,
                            timestampUs = frame.timestampUs,
                            shotIndex = shot.index,
                            sharpness = frame.sharpness,
                            yPlane = frame.yPlane,
                            width = frame.width,
                            height = frame.height
                        )
                    )
                }
            }
        }

        // If we have too many candidates, trim by sharpness
        return if (candidates.size > config.targetCandidateCount.last) {
            candidates.sortedByDescending { it.sharpness }
                .take(config.targetCandidateCount.last)
        } else {
            candidates
        }
    }
}
