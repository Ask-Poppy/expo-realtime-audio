package expo.modules.audiostream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.launch

private const val AUDIO_CHUNK_EVENT = "onAudioChunk"
private const val ERROR_EVENT = "onError"
private const val PLAYBACK_COMPLETE_EVENT = "onPlaybackComplete"

class AudioStreamModule : Module() {
    private val context: Context
        get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

    private var recorder: AudioStreamRecorder? = null
    private var player: AudioStreamPlayer? = null
    private var isPrepared = false

    override fun definition() =
        ModuleDefinition {
            Name("AudioStream")

            Events(AUDIO_CHUNK_EVENT, ERROR_EVENT, PLAYBACK_COMPLETE_EVENT)

            OnDestroy {
                cleanup()
            }

            AsyncFunction("requestPermissions") { promise: Promise ->
                Permissions.askForPermissionsWithPermissionsManager(
                    appContext.permissions,
                    promise,
                    Manifest.permission.RECORD_AUDIO,
                )
            }

            AsyncFunction("prepare") {
                if (!hasRecordingPermission()) {
                    return@AsyncFunction
                }

                try {
                    configureAudioSession()
                    isPrepared = true
                } catch (e: Exception) {
                    throw PrepareException(e)
                }
            }

            AsyncFunction("startRecording") { config: RecordingConfig ->
                if (!hasRecordingPermission()) {
                    throw AudioPermissionException()
                }

                if (recorder?.recording == true) {
                    throw AlreadyRecordingException()
                }

                try {
                    configureAudioSession()

                    recorder =
                        AudioStreamRecorder(
                            onAudioChunk = { data, position, chunkSize, totalSize ->
                                appContext.mainQueue.launch {
                                    sendEvent(
                                        AUDIO_CHUNK_EVENT,
                                        bundleOf(
                                            "data" to data,
                                            "position" to position,
                                            "chunkSize" to chunkSize,
                                            "totalSize" to totalSize,
                                        ),
                                    )
                                }
                            },
                            onError = { code, message ->
                                appContext.mainQueue.launch {
                                    sendEvent(
                                        ERROR_EVENT,
                                        bundleOf(
                                            "code" to code,
                                            "message" to message,
                                        ),
                                    )
                                }
                            },
                        )

                    recorder?.start(config)
                } catch (e: Exception) {
                    when (e) {
                        is AlreadyRecordingException -> throw e
                        is AudioPermissionException -> throw e
                        else -> throw RecordingStartException(e)
                    }
                }
            }

            AsyncFunction("stopRecording") {
                recorder?.stop()
                recorder = null
            }

            AsyncFunction("startPlayback") { config: PlaybackConfig ->
                try {
                    configureAudioSession()

                    player =
                        AudioStreamPlayer(
                            onPlaybackComplete = {
                                appContext.mainQueue.launch {
                                    sendEvent(PLAYBACK_COMPLETE_EVENT, bundleOf())
                                }
                            },
                            onError = { code, message ->
                                appContext.mainQueue.launch {
                                    sendEvent(
                                        ERROR_EVENT,
                                        bundleOf(
                                            "code" to code,
                                            "message" to message,
                                        ),
                                    )
                                }
                            },
                        )

                    player?.start(config)
                } catch (e: Exception) {
                    throw PlaybackStartException(e)
                }
            }

            AsyncFunction("playChunk") { base64Data: String ->
                player?.playChunk(base64Data)
                    ?: throw PlaybackNotStartedException()
            }

            AsyncFunction("endPlayback") {
                player?.endPlayback()
            }

            AsyncFunction("stopPlayback") {
                player?.stop()
                player = null
            }
        }

    private fun hasRecordingPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun configureAudioSession() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun cleanup() {
        recorder?.release()
        recorder = null
        player?.release()
        player = null
        isPrepared = false

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
            // Context may be lost during cleanup
        }
    }
}
