package expo.modules.audiostream

import expo.modules.kotlin.exception.CodedException

internal class AudioPermissionException : CodedException("PERMISSION_DENIED", "RECORD_AUDIO permission has not been granted")

internal class AlreadyRecordingException : CodedException("ALREADY_RECORDING", "Recording is already in progress")

internal class NotRecordingException : CodedException("NOT_RECORDING", "No recording is in progress")

internal class RecordingStartException(
    cause: Throwable? = null,
) : CodedException("START_ERROR", "Failed to start recording: ${cause?.message ?: "Unknown error"}", cause)

internal class AudioRecordInitException(
    message: String,
) : CodedException("INIT_ERROR", "Failed to initialize AudioRecord: $message")

internal class PlaybackStartException(
    cause: Throwable? = null,
) : CodedException("PLAYBACK_ERROR", "Failed to start playback: ${cause?.message ?: "Unknown error"}", cause)

internal class PlaybackNotStartedException : CodedException("PLAYBACK_NOT_STARTED", "Playback has not been started")

internal class InvalidBase64Exception : CodedException("CHUNK_ERROR", "Invalid base64 data")

internal class PrepareException(
    cause: Throwable? = null,
) : CodedException("PREPARE_ERROR", "Failed to prepare audio: ${cause?.message ?: "Unknown error"}", cause)
