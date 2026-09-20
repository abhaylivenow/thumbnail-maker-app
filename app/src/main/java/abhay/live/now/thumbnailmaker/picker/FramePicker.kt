package abhay.live.now.thumbnailmaker.picker

import android.content.Context
import android.net.Uri
import android.util.Log
import abhay.live.now.thumbnailmaker.BuildConfig
import abhay.live.now.thumbnailmaker.picker.audio.AudioAnalyzer
import abhay.live.now.thumbnailmaker.picker.decode.VideoDecoder
import abhay.live.now.thumbnailmaker.picker.extract.FullResExtractor
import abhay.live.now.thumbnailmaker.picker.features.FrameFeatures
import abhay.live.now.thumbnailmaker.picker.pool.CandidatePool
import abhay.live.now.thumbnailmaker.picker.scoring.FrameScorer
import abhay.live.now.thumbnailmaker.picker.segment.ShotSegmenter
import abhay.live.now.thumbnailmaker.picker.selection.MmrSelector
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

class FramePicker(
    private val context: Context,
    private val config: Config = Config()
) {

    fun pick(uri: Uri): Flow<PickerEvent> = flow {
            val decoder = VideoDecoder(context, config)
            val videoInfo = decoder.getVideoInfo(uri)

            if (BuildConfig.DEBUG) {
                Log.d("FramePicker", "Video: ${videoInfo.width}x${videoInfo.height}, " +
                    "rotation=${videoInfo.rotation}, duration=${videoInfo.durationUs}us")
            }

            // Phase 1 & 5: Decode video + analyze audio concurrently
            emit(PickerEvent.StageUpdate("Scanning video", 0f))

            val scanFrames: List<ScanFrame>
            val audioPeaks: List<AudioPeak>
            val audioAvailable: Boolean

            coroutineScope {
                val audioJob = async {
                    try {
                        val analyzer = AudioAnalyzer(context, config)
                        val peaks = analyzer.analyze(uri)
                        peaks to true
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w("FramePicker", "Audio failed", e)
                        emptyList<AudioPeak>() to false
                    }
                }

                scanFrames = decoder.decode(uri).toList()
                val audioResult = audioJob.await()
                audioPeaks = audioResult.first
                audioAvailable = audioResult.second
            }

            if (scanFrames.isEmpty()) {
                emit(PickerEvent.Error("No frames decoded from video"))
                return@flow
            }

            emit(PickerEvent.StageUpdate("Scanning video", 1f))
            if (BuildConfig.DEBUG) Log.d("FramePicker", "Decoded ${scanFrames.size} scan frames")

            // Phase 2: Compute features
            emit(PickerEvent.StageUpdate("Analyzing frames", 0f))
            val allFeatures = scanFrames.mapIndexed { idx, frame ->
                currentCoroutineContext().ensureActive()
                val feature = FrameFeatures.compute(frame)
                emit(PickerEvent.StageUpdate("Analyzing frames",
                    (idx + 1).toFloat() / scanFrames.size))
                feature
            }

            // Reject poor quality frames
            val features = FrameFeatures.rejectFrames(allFeatures, config)
            if (BuildConfig.DEBUG) {
                Log.d("FramePicker", "After rejection: ${features.size}/${allFeatures.size} frames")
            }

            if (features.isEmpty()) {
                emit(PickerEvent.Error("All frames rejected as poor quality"))
                return@flow
            }

            // Phase 3: Shot segmentation
            emit(PickerEvent.StageUpdate("Segmenting shots", 0f))
            val shots = ShotSegmenter.segment(features, config)
            emit(PickerEvent.StageUpdate("Segmenting shots", 1f))
            if (BuildConfig.DEBUG) Log.d("FramePicker", "Found ${shots.size} shots")

            // Phase 4: Candidate pool
            emit(PickerEvent.StageUpdate("Selecting candidates", 0f))
            val featureMap = features.associateBy { it.index }
            val candidates = CandidatePool.selectCandidates(shots, featureMap, config)
            emit(PickerEvent.StageUpdate("Selecting candidates", 1f))
            if (BuildConfig.DEBUG) Log.d("FramePicker", "Pool: ${candidates.size} candidates")

            if (candidates.isEmpty()) {
                emit(PickerEvent.Error("No candidate frames selected"))
                return@flow
            }

            // Phase 6: Scoring
            emit(PickerEvent.StageUpdate("Scoring candidates", 0f))
            val scorer = FrameScorer(config)
            scorer.score(candidates, shots, audioPeaks, audioAvailable)
            emit(PickerEvent.StageUpdate("Scoring candidates", 1f))

            // Phase 7: MMR selection
            emit(PickerEvent.StageUpdate("Selecting best frames", 0f))
            val selector = MmrSelector(context, config)
            val selected = selector.select(candidates, shots, videoInfo.durationUs)
            emit(PickerEvent.StageUpdate("Selecting best frames", 1f))
            if (BuildConfig.DEBUG) Log.d("FramePicker", "Selected ${selected.size} frames")

            // Phase 8: Full-res extraction
            emit(PickerEvent.StageUpdate("Extracting full resolution", 0f))
            val extractor = FullResExtractor(context)
            val results = extractor.extract(uri, selected, videoInfo.rotation)
            emit(PickerEvent.StageUpdate("Extracting full resolution", 1f))

            // Emit individual candidates progressively
            for (frame in results) {
                if (frame.fullResBitmap != null) {
                    emit(PickerEvent.Candidate(frame))
                }
            }

            val finalResults = results.filter { it.fullResBitmap != null }
            emit(PickerEvent.Complete(finalResults))

            if (BuildConfig.DEBUG) {
                Log.d("FramePicker", "Complete: ${finalResults.size} thumbnails")
            }
    }.catch { e ->
        if (BuildConfig.DEBUG) Log.e("FramePicker", "Pipeline error", e)
        emit(PickerEvent.Error(e.message ?: "Unknown error", e))
    }
}
