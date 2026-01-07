package expo.modules.audiostream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioStreamPlayer(
    private val onPlaybackComplete: () -> Unit,
    private val onError: (code: String, message: String) -> Unit,
) {
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val isPlaying = AtomicBoolean(false)
    private val playbackEnded = AtomicBoolean(false)

    private var sampleRate: Int = 24000
    private var channels: Int = 1

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val buffersScheduled = AtomicInteger(0)
    private val buffersCompleted = AtomicInteger(0)

    val playing: Boolean
        get() = isPlaying.get()

    fun start(config: PlaybackConfig) {
        if (isPlaying.get()) {
            stop()
        }

        sampleRate = config.sampleRate
        channels = config.channels

        val channelConfig =
            if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            throw PlaybackStartException(Exception("Invalid buffer size configuration"))
        }

        try {
            audioTrack =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build(),
                    ).setBufferSizeInBytes(minBufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                audioTrack?.release()
                audioTrack = null
                throw PlaybackStartException(Exception("AudioTrack failed to initialize"))
            }
        } catch (e: Exception) {
            throw PlaybackStartException(e)
        }

        isPlaying.set(true)
        playbackEnded.set(false)
        buffersScheduled.set(0)
        buffersCompleted.set(0)
        audioQueue.clear()

        audioTrack?.play()
        startPlaybackThread()
    }

    private fun startPlaybackThread() {
        playbackThread =
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

                while (isPlaying.get()) {
                    val data = audioQueue.poll()
                    if (data != null) {
                        val written = audioTrack?.write(data, 0, data.size) ?: -1
                        if (written > 0) {
                            buffersCompleted.incrementAndGet()
                            checkPlaybackComplete()
                        } else if (written < 0) {
                            onError("PLAYBACK_ERROR", "Error writing to AudioTrack: $written")
                        }
                    } else {
                        // No data available, sleep briefly to avoid busy waiting
                        Thread.sleep(5)
                    }
                }
            }, "AudioStreamPlayer").apply {
                start()
            }
    }

    fun playChunk(base64Data: String) {
        if (!isPlaying.get()) {
            throw PlaybackNotStartedException()
        }

        val data =
            try {
                Base64.decode(base64Data, Base64.DEFAULT)
            } catch (e: Exception) {
                throw InvalidBase64Exception()
            }

        buffersScheduled.incrementAndGet()
        audioQueue.offer(data)
    }

    fun endPlayback() {
        playbackEnded.set(true)
        checkPlaybackComplete()
    }

    private fun checkPlaybackComplete() {
        if (playbackEnded.get() &&
            buffersCompleted.get() >= buffersScheduled.get() &&
            buffersScheduled.get() > 0
        ) {
            onPlaybackComplete()
            cleanup()
        }
    }

    fun stop() {
        cleanup()
    }

    private fun cleanup() {
        isPlaying.set(false)
        playbackEnded.set(false)
        buffersScheduled.set(0)
        buffersCompleted.set(0)
        audioQueue.clear()

        playbackThread?.join(1000)
        playbackThread = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun release() {
        cleanup()
    }
}
