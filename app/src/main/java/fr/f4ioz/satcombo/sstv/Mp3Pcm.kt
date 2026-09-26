/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes one of SatMe's own MP3 recordings back to mono 16-bit PCM.
 *
 * The recordings are written by LAME inside [fr.f4ioz.satcombo.audio.PassRecorder],
 * so they are plain MPEG-1 Layer III files; Android's own [MediaCodec] handles
 * them without adding a decoding library to the build. The audio is handed to
 * the caller in the order it comes out of the codec, which is all the SSTV
 * engine needs — it is a streaming decoder.
 *
 * Nothing here is Compose- or coroutine-aware: it blocks, and the caller runs it
 * on a background thread.
 */
object Mp3Pcm {

    private const val TIMEOUT_US = 10_000L

    /**
     * Feeds [onPcm] with successive buffers of mono PCM.
     *
     * The callback receives the buffer, the number of valid samples, the sample
     * rate of the stream and how far through the file we are (0..1). Returning
     * false asks the decode to stop early.
     *
     * @return the sample rate that was used, or 0 if the file could not be read.
     */
    fun decode(
        file: File,
        onPcm: (pcm: ShortArray, count: Int, sampleRate: Int, fraction: Float) -> Boolean
    ): Int {
        if (!file.isFile || file.length() == 0L) return 0

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var rate = 0
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; format = f; break }
            }
            val fmt = format ?: return 0
            if (track < 0) return 0
            extractor.selectTrack(track)

            rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1)
            if (channels < 1) channels = 1
            val durationUs = runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }
                .getOrDefault(0L).coerceAtLeast(1L)

            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return 0
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var mono = ShortArray(4096)
            var sawInputEnd = false
            var sawOutputEnd = false
            var cancelled = false

            while (!sawOutputEnd && !cancelled) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)
                        val n = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIndex)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val got = shorts.remaining()
                                val frames = got / channels
                                if (mono.size < frames) mono = ShortArray(frames)
                                if (channels == 1) {
                                    shorts.get(mono, 0, frames)
                                } else {
                                    // Downmix: an SSTV tone is the same in both
                                    // channels, and averaging keeps the level sane.
                                    var w = 0
                                    var acc: Int
                                    for (f in 0 until frames) {
                                        acc = 0
                                        for (c in 0 until channels) acc += shorts.get().toInt()
                                        mono[w++] = (acc / channels).toShort()
                                    }
                                }
                                val fraction =
                                    (info.presentationTimeUs.toFloat() / durationUs)
                                        .coerceIn(0f, 1f)
                                if (frames > 0 && !onPcm(mono, frames, rate, fraction)) {
                                    cancelled = true
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEnd = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        rate = runCatching { nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            .getOrDefault(rate)
                        channels = runCatching { nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(channels).coerceAtLeast(1)
                    }
                    // INFO_TRY_AGAIN_LATER and the deprecated buffers-changed
                    // case both simply mean "loop again".
                }
            }
        } catch (_: Exception) {
            return rate
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
        return rate
    }
}
