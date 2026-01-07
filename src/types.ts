export interface AudioChunkEvent {
  data: string;
  position: number;
  chunkSize: number;
  totalSize: number;
}

export interface AudioErrorEvent {
  code: string;
  message: string;
}

export interface AudioSessionConfig {
  /** Allow audio to route through Bluetooth devices (default: true) */
  allowBluetooth?: boolean;
  /** Mix audio with other apps instead of interrupting (default: true) */
  mixWithOthers?: boolean;
  /** Route audio to speaker by default instead of earpiece (default: true) */
  defaultToSpeaker?: boolean;
}

export interface RecordingConfig {
  /** Target sample rate in Hz (default: 16000) */
  sampleRate?: number;
  /** Number of audio channels, 1 for mono, 2 for stereo (default: 1) */
  channels?: number;
  /** Interval in milliseconds between audio chunk emissions (default: 50) */
  intervalMs?: number;
  /** Buffer size in frames. Smaller = lower latency, larger = more stable (default: 1024) */
  bufferSize?: 256 | 512 | 1024 | 2048;
  /** iOS audio session configuration */
  audioSession?: AudioSessionConfig;
}

export interface PlaybackConfig {
  sampleRate?: number;
  channels?: number;
}

export interface RecordingResult {
  sampleRate: number;
  channels: number;
  bitDepth: number;
  mimeType: string;
}

export interface PermissionResult {
  granted: boolean;
  status: "granted" | "denied" | "undetermined";
}
