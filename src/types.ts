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

export interface RecordingConfig {
  sampleRate?: number;
  channels?: number;
  intervalMs?: number;
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
