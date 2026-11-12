package com.pttradio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "PTTAudio"
private const val SAMPLE_RATE = 16_000            // wideband voice
private const val FRAME_BYTES = SAMPLE_RATE / 50 * 2 // 20 ms of 16-bit mono
private const val FRAME_US = 20_000L
private const val BIT_RATE = 24_000

/** Microphone -> Opus. One instance per transmission; [onPacket] gets one Opus packet per 20 ms. */
class Talker(private val onPacket: (ByteArray) -> Unit) {
    @Volatile private var running = true

    init {
        thread(name = "ptt-tx") { run() }
    }

    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission") // the UI only keys the radio once RECORD_AUDIO is granted
    private fun run() {
        var rec: AudioRecord? = null
        var enc: MediaCodec? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // echo cancelling + noise suppression
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_BYTES * 4)
            )
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            rec.startRecording()

            val pcm = ByteArray(FRAME_BYTES)
            val info = MediaCodec.BufferInfo()
            var pts = 0L
            while (running) {
                var got = 0
                while (got < FRAME_BYTES && running) {
                    val n = rec.read(pcm, got, FRAME_BYTES - got)
                    if (n <= 0) { running = false; break }
                    got += n
                }
                if (got < FRAME_BYTES) break
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    enc.getInputBuffer(i)!!.apply { clear(); put(pcm) }
                    enc.queueInputBuffer(i, 0, FRAME_BYTES, pts, 0)
                    pts += FRAME_US
                }
                drain(enc, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "transmit failed", e)
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            runCatching { enc?.stop() }
            runCatching { enc?.release() }
        }
    }

    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val o = enc.dequeueOutputBuffer(info, 0)
            if (o >= 0) {
                // The first buffer is the stream header; receivers build their own, so skip it.
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val out = ByteArray(info.size)
                    enc.getOutputBuffer(o)!!.apply { position(info.offset); get(out) }
                    onPacket(out)
                }
                enc.releaseOutputBuffer(o, false)
            } else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return
            }
        }
    }
}

/** Opus -> loudspeaker. One instance per incoming transmission. */
class Listener {
    private val queue = LinkedBlockingQueue<ByteArray>()

    init {
        thread(name = "ptt-rx") { run() }
    }

    fun offer(packet: ByteArray) {
        if (queue.size > 100) queue.poll() // more than 2 s behind: drop the oldest audio
        queue.offer(packet)
    }

    /** Play out what is queued, then shut down. */
    fun finish() {
        queue.offer(END)
    }

    private fun run() {
        var dec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", nanos(0))           // pre-skip
                setByteBuffer("csd-2", nanos(80_000_000))  // seek pre-roll
            }
            dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            dec.configure(fmt, null, null, 0)
            dec.start()

            // Small jitter buffer: wait for ~60 ms of audio so network hiccups don't cause stutter.
            val t0 = System.currentTimeMillis()
            while (queue.size < 3 && System.currentTimeMillis() - t0 < 200) Thread.sleep(10)

            val info = MediaCodec.BufferInfo()
            var pts = 0L
            var ended = false
            while (!ended) {
                val p = queue.poll(20, TimeUnit.MILLISECONDS)
                if (p === END) {
                    ended = true
                } else if (p != null) {
                    val i = dec.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        dec.getInputBuffer(i)!!.apply { clear(); put(p) }
                        dec.queueInputBuffer(i, 0, p.size, pts, 0)
                        pts += FRAME_US
                    }
                }
                while (true) {
                    val o = dec.dequeueOutputBuffer(info, if (ended) 30_000 else 0)
                    if (o >= 0) {
                        if (info.size > 0) {
                            if (track == null) track = newTrack(dec.outputFormat)
                            val buf = dec.getOutputBuffer(o)!!
                            buf.position(info.offset).limit(info.offset + info.size)
                            track.write(buf, info.size, AudioTrack.WRITE_BLOCKING)
                        }
                        dec.releaseOutputBuffer(o, false)
                    } else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        break
                    }
                }
            }
            Thread.sleep(250) // let the tail of the transmission leave the speaker
        } catch (e: Exception) {
            Log.e(TAG, "receive failed", e)
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { dec?.stop() }
            runCatching { dec?.release() }
        }
    }

    // The decoder decides the output rate (Android's Opus decoder emits 48 kHz), so ask it.
    private fun newTrack(out: MediaFormat): AudioTrack {
        val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val mask = if (out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // loudspeaker, like a radio
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    private companion object {
        val END = ByteArray(0)

        /** Minimal Ogg-Opus identification header (RFC 7845 §5.1): mono, no pre-skip, no gain. */
        fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)               // version
            put(1)               // channels
            putShort(0)          // pre-skip
            putInt(SAMPLE_RATE)  // original input rate
            putShort(0)          // output gain
            put(0)               // channel mapping family
        }.array()

        fun nanos(v: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(v); flip() }
    }
}
