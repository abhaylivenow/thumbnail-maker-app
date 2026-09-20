package abhay.live.now.thumbnailmaker.picker.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import abhay.live.now.thumbnailmaker.BuildConfig
import abhay.live.now.thumbnailmaker.picker.AudioPeak
import abhay.live.now.thumbnailmaker.picker.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

class AudioAnalyzer(private val context: Context, private val config: Config) {

    suspend fun analyze(uri: Uri): List<AudioPeak> = withContext(Dispatchers.IO) {
        try {
            val pcm = decodeAudio(uri) ?: return@withContext emptyList()
            val peaks = findPeaks(pcm)
            peaks
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("AudioAnalyzer", "Audio analysis failed", e)
            emptyList()
        }
    }

    private suspend fun decodeAudio(uri: Uri): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val pcmChunks = mutableListOf<ShortArray>()

            while (!outputDone) {
                coroutineContext.ensureActive()

                // Feed input
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIdx) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outIdx)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val samples = ShortArray(bufferInfo.size / 2)
                            outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)

                            // Downmix to mono if needed
                            val mono = if (channels > 1) downmixToMono(samples, channels) else samples
                            pcmChunks.add(mono)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            // Concatenate all chunks
            val totalSamples = pcmChunks.sumOf { it.size }
            val fullPcm = ShortArray(totalSamples)
            var offset = 0
            for (chunk in pcmChunks) {
                chunk.copyInto(fullPcm, offset)
                offset += chunk.size
            }

            // Resample to target sample rate if needed
            return if (sampleRate != config.audioSampleRate) {
                resample(fullPcm, sampleRate, config.audioSampleRate)
            } else {
                fullPcm
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun findPeaks(pcm: ShortArray): List<AudioPeak> {
        if (pcm.isEmpty()) return emptyList()

        val windowSize = config.audioWindowSamples
        val hopSize = config.audioHopSamples
        val sampleRate = config.audioSampleRate

        // Compute RMS for each window
        val rmsValues = mutableListOf<Pair<Long, Float>>() // timestampUs, rms
        var pos = 0

        while (pos + windowSize <= pcm.size) {
            val rms = computeRms(pcm, pos, windowSize)
            val timestampUs = (pos.toLong() * 1_000_000L) / sampleRate
            rmsValues.add(timestampUs to rms)
            pos += hopSize
        }

        if (rmsValues.isEmpty()) return emptyList()

        // Find top-k peaks with minimum gap
        val minGapUs = config.audioPeakGapMs * 1000
        val sorted = rmsValues.sortedByDescending { it.second }
        val peaks = mutableListOf<AudioPeak>()

        for ((ts, rms) in sorted) {
            val tooClose = peaks.any { existing ->
                kotlin.math.abs(existing.timestampUs - ts) < minGapUs
            }
            if (!tooClose) {
                peaks.add(AudioPeak(ts, rms))
            }
            if (peaks.size >= 20) break // reasonable upper bound
        }

        return peaks.sortedBy { it.timestampUs }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        val monoLength = samples.size / channels
        val mono = ShortArray(monoLength)
        for (i in 0 until monoLength) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += samples[i * channels + ch].toInt()
            }
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    private fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        val ratio = srcRate.toDouble() / dstRate
        val outputLen = (input.size / ratio).toInt()
        val output = ShortArray(outputLen)
        for (i in 0 until outputLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceAtMost(input.size - 1)
            output[i] = input[idx]
        }
        return output
    }

    companion object {
        fun computeRms(pcm: ShortArray, offset: Int, length: Int): Float {
            var sumSq = 0.0
            for (i in offset until (offset + length).coerceAtMost(pcm.size)) {
                val sample = pcm[i].toDouble() / Short.MAX_VALUE
                sumSq += sample * sample
            }
            return sqrt(sumSq / length).toFloat()
        }
    }
}
