package expo.modules.audiostream

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamRecorder(
    private val onAudioChunk: (data: String, position: Long, chunkSize: Int, totalSize: Long) -> Unit,
    private val onError: (code: String, message: String) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    private var sampleRate: Int = 16000
    private var channels: Int = 1
    private var intervalMs: Int = 50
    private var bufferSize: Int = 1024

    private var startTime: Long = 0
    private var totalSize: Long = 0
    private val accumulatedData = ByteArrayOutputStream()
    private var lastEmissionTime: Long = 0

    val recording: Boolean
        get() = isRecording.get()

    fun start(config: RecordingConfig): Map<String, Any> {
        if (isRecording.get()) {
            throw AlreadyRecordingException()
        }

        sampleRate = config.sampleRate
        channels = config.channels
        intervalMs = config.intervalMs
        bufferSize = config.bufferSize

        val channelConfig =
            if (channels == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw AudioRecordInitException("Invalid buffer size configuration")
        }

        val actualBufferSize = maxOf(minBufferSize * 2, bufferSize * 2)

        try {
            audioRecord =
                AudioRecord
                    .Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build(),
                    ).setBufferSizeInBytes(actualBufferSize)
                    .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                throw AudioRecordInitException("AudioRecord failed to initialize")
            }
        } catch (e: SecurityException) {
            throw AudioPermissionException()
        } catch (e: Exception) {
            throw RecordingStartException(e)
        }

        isRecording.set(true)
        startTime = System.currentTimeMillis()
        totalSize = 0
        accumulatedData.reset()
        lastEmissionTime = System.currentTimeMillis()

        audioRecord?.startRecording()
        startRecordingThread(minBufferSize)

        return mapOf(
            "sampleRate" to sampleRate,
            "channels" to channels,
            "bitDepth" to 16,
            "mimeType" to "audio/pcm",
        )
    }

    private fun startRecordingThread(bufferSize: Int) {
        recordingThread =
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

                val buffer = ByteArray(bufferSize)

                while (isRecording.get()) {
                    val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1

                    if (bytesRead > 0) {
                        synchronized(accumulatedData) {
                            accumulatedData.write(buffer, 0, bytesRead)
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastEmissionTime >= intervalMs) {
                            emitAudioChunk()
                            lastEmissionTime = now
                        }
                    } else if (bytesRead < 0) {
                        onError("RECORDING_ERROR", "Error reading from AudioRecord: $bytesRead")
                        break
                    }
                }
            }, "AudioStreamRecorder").apply {
                start()
            }
    }

    private fun emitAudioChunk() {
        val dataToEmit: ByteArray
        synchronized(accumulatedData) {
            if (accumulatedData.size() == 0) return
            dataToEmit = accumulatedData.toByteArray()
            accumulatedData.reset()
        }

        totalSize += dataToEmit.size
        val position = System.currentTimeMillis() - startTime
        val base64Data = Base64.encodeToString(dataToEmit, Base64.NO_WRAP)

        onAudioChunk(base64Data, position, dataToEmit.size, totalSize)
    }

    fun stop() {
        if (!isRecording.get()) {
            return
        }

        isRecording.set(false)

        // Emit any remaining data
        emitAudioChunk()

        recordingThread?.join(1000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        startTime = 0
        totalSize = 0
        accumulatedData.reset()
    }

    fun release() {
        stop()
    }
}
