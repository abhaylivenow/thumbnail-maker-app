package abhay.live.now.thumbnailmaker.picker.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import abhay.live.now.thumbnailmaker.BuildConfig
import abhay.live.now.thumbnailmaker.picker.CandidateFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class FullResExtractor(private val context: Context) {

    suspend fun extract(
        uri: Uri,
        candidates: List<CandidateFrame>,
        rotation: Int
    ): List<CandidateFrame> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()

        return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extractWithRetriever(uri, candidates, rotation)
        } else {
            extractWithCodec(uri, candidates, rotation)
        }
    }

    private fun extractWithRetriever(
        uri: Uri,
        candidates: List<CandidateFrame>,
        rotation: Int
    ): List<CandidateFrame> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            for (candidate in candidates) {
                try {
                    val bitmap = retriever.getFrameAtTime(
                        candidate.timestampUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    if (bitmap != null) {
                        candidate.fullResBitmap = applyRotation(bitmap, rotation)
                        if (candidate.fullResBitmap !== bitmap) bitmap.recycle()
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.w("FullResExtractor", "Failed frame at ${candidate.timestampUs}", e)
                    }
                }
            }
        } finally {
            retriever.release()
        }
        return candidates
    }

    private suspend fun extractWithCodec(
        uri: Uri,
        candidates: List<CandidateFrame>,
        rotation: Int
    ): List<CandidateFrame> {
        val sortedTargets = candidates.sortedBy { it.timestampUs }
        val targetTimes = sortedTargets.map { it.timestampUs }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectVideoTrack(extractor)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

            // Request YUV420 flexible so getOutputImage() works
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0) // null surface = buffer mode
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var currentTargetIdx = 0
            val toleranceUs = 100_000L // 100ms tolerance

            while (!outputDone && currentTargetIdx < targetTimes.size) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIdx) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (!isEos && currentTargetIdx < targetTimes.size &&
                        kotlin.math.abs(pts - targetTimes[currentTargetIdx]) <= toleranceUs
                    ) {
                        val image = codec.getOutputImage(outIdx)
                        if (image != null) {
                            try {
                                val bitmap = imageToBitmap(image)
                                val rotated = applyRotation(bitmap, rotation)
                                sortedTargets[currentTargetIdx].fullResBitmap = rotated
                                if (rotated !== bitmap) bitmap.recycle()
                            } finally {
                                image.close()
                            }
                        }
                        currentTargetIdx++
                    }

                    codec.releaseOutputBuffer(outIdx, false)

                    if (isEos) {
                        outputDone = true
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        return candidates
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val argb = IntArray(width * height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val y = (yBuffer.get(row * yRowStride + col).toInt() and 0xFF)
                val uvRow = row / 2
                val uvCol = col / 2
                val uvIdx = uvRow * uvRowStride + uvCol * uvPixelStride

                val u = (uBuffer.get(uvIdx).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvIdx).toInt() and 0xFF) - 128

                var r = y + (1.370705f * v).toInt()
                var g = y - (0.337633f * u).toInt() - (0.698001f * v).toInt()
                var b = y + (1.732446f * u).toInt()

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                argb[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun applyRotation(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        throw IllegalArgumentException("No video track found")
    }
}
