package abhay.live.now.thumbnailmaker.picker.decode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import abhay.live.now.thumbnailmaker.BuildConfig
import abhay.live.now.thumbnailmaker.picker.Config
import abhay.live.now.thumbnailmaker.picker.ScanFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

class VideoDecoder(private val context: Context, private val config: Config) {

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationUs: Long,
        val mime: String
    )

    fun getVideoInfo(uri: Uri): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectVideoTrack(extractor)
            val format = extractor.getTrackFormat(trackIndex)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            return VideoInfo(width, height, rotation, durationUs, mime)
        } finally {
            extractor.release()
        }
    }

    fun decode(uri: Uri): Flow<ScanFrame> = flow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectVideoTrack(extractor)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            val origWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val origHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

            // Compute scan dimensions
            val scale = config.scanEdgePx.toFloat() / maxOf(origWidth, origHeight)
            val scanWidth = if (scale < 1f) (origWidth * scale).roundToInt().let { it - it % 2 } else origWidth
            val scanHeight = if (scale < 1f) (origHeight * scale).roundToInt().let { it - it % 2 } else origHeight

            val frameIntervalUs = (1_000_000.0 / config.scanFps).toLong()

            // Configure codec to decode to buffers (no Surface)
            // Request YUV420 flexible output
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0) // null surface = decode to buffer
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var nextSampleUs = 0L
            var frameIndex = 0

            while (!outputDone) {
                coroutineContext.ensureActive()

                // Feed input
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
                            val sampleTime = extractor.sampleTime
                            val flags = extractor.sampleFlags
                            codec.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)

                            // Track keyframe status before advancing
                            extractor.advance()
                            // Skip ahead if current time is far before next desired sample
                            while (extractor.sampleTime >= 0 &&
                                extractor.sampleTime < nextSampleUs - frameIntervalUs / 2
                            ) {
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx >= 0 -> {
                        val presentationTimeUs = bufferInfo.presentationTimeUs
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val shouldCapture = presentationTimeUs >= nextSampleUs && !isEos

                        if (shouldCapture) {
                            // Use getOutputImage() — safe, no ImageReader needed
                            val image = codec.getOutputImage(outIdx)
                            if (image != null) {
                                try {
                                    val imgWidth = image.width
                                    val imgHeight = image.height
                                    val yPlane = image.planes[0]
                                    val rowStride = yPlane.rowStride
                                    val yBuf = yPlane.buffer

                                    // Downsample Y plane if codec output is larger than scan dims
                                    val yData: ByteArray
                                    val outW: Int
                                    val outH: Int

                                    if (imgWidth > scanWidth || imgHeight > scanHeight) {
                                        outW = scanWidth
                                        outH = scanHeight
                                        yData = downsampleYPlane(
                                            yBuf, imgWidth, imgHeight, rowStride,
                                            outW, outH
                                        )
                                    } else {
                                        outW = imgWidth
                                        outH = imgHeight
                                        yData = ByteArray(outW * outH)
                                        for (row in 0 until outH) {
                                            yBuf.position(row * rowStride)
                                            yBuf.get(yData, row * outW, min(outW, rowStride))
                                        }
                                    }

                                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                    val scanFrame = ScanFrame(
                                        index = frameIndex,
                                        timestampUs = presentationTimeUs,
                                        yPlane = yData,
                                        width = outW,
                                        height = outH,
                                        isKeyFrame = isKeyFrame
                                    )
                                    emit(scanFrame)
                                    frameIndex++
                                    nextSampleUs = presentationTimeUs + frameIntervalUs
                                } finally {
                                    image.close()
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outIdx, false)

                        if (isEos) {
                            outputDone = true
                        }
                    }

                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (BuildConfig.DEBUG) {
                            Log.d("VideoDecoder", "Output format changed: ${codec.outputFormat}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("VideoDecoder", "Decode error", e)
            throw e
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    private fun downsampleYPlane(
        srcBuf: java.nio.ByteBuffer,
        srcW: Int, srcH: Int, rowStride: Int,
        dstW: Int, dstH: Int
    ): ByteArray {
        val dst = ByteArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH

        for (dstRow in 0 until dstH) {
            val srcRow = (dstRow * yRatio).toInt().coerceAtMost(srcH - 1)
            for (dstCol in 0 until dstW) {
                val srcCol = (dstCol * xRatio).toInt().coerceAtMost(srcW - 1)
                dst[dstRow * dstW + dstCol] = srcBuf.get(srcRow * rowStride + srcCol)
            }
        }
        return dst
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        throw IllegalArgumentException("No video track found")
    }
}
