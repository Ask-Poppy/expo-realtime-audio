package expo.modules.audiostream

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.kotlin.types.Enumerable

class AudioSessionConfig(
    @Field val allowBluetooth: Boolean = true,
    @Field val mixWithOthers: Boolean = true,
    @Field val defaultToSpeaker: Boolean = true,
) : Record

class RecordingConfig(
    @Field val sampleRate: Int = 16000,
    @Field val channels: Int = 1,
    @Field val intervalMs: Int = 50,
    @Field val bufferSize: Int = 1024,
    @Field val audioSession: AudioSessionConfig = AudioSessionConfig(),
) : Record

class PlaybackConfig(
    @Field val sampleRate: Int = 24000,
    @Field val channels: Int = 1,
) : Record

enum class BufferSize(
    val value: Int,
) : Enumerable {
    BUFFER_256(256),
    BUFFER_512(512),
    BUFFER_1024(1024),
    BUFFER_2048(2048),
}
