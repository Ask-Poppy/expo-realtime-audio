import { NativeModule, requireNativeModule } from "expo";
import type {
  AudioChunkEvent,
  AudioErrorEvent,
  PermissionResult,
  RecordingResult,
} from "./types";

type AudioStreamEvents = {
  onAudioChunk: (event: AudioChunkEvent) => void;
  onError: (event: AudioErrorEvent) => void;
  onPlaybackComplete: () => void;
};

declare class AudioStreamModuleType extends NativeModule<AudioStreamEvents> {
  prepare(): Promise<void>;
  startRecording(config: {
    sampleRate: number;
    channels: number;
    intervalMs: number;
    bufferSize: number;
    audioSession: {
      allowBluetooth: boolean;
      mixWithOthers: boolean;
      defaultToSpeaker: boolean;
    };
  }): Promise<RecordingResult>;
  stopRecording(): Promise<void>;
  requestPermissions(): Promise<PermissionResult>;
  startPlayback(config: {
    sampleRate?: number;
    channels?: number;
  }): Promise<void>;
  playChunk(base64Data: string): Promise<void>;
  endPlayback(): Promise<void>;
  stopPlayback(): Promise<void>;
}

export default requireNativeModule<AudioStreamModuleType>("AudioStream");
