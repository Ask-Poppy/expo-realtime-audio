import { useCallback, useEffect, useRef, useState } from "react";
import AudioStreamModule from "./AudioStreamModule";
import type {
  AudioChunkEvent,
  AudioErrorEvent,
  RecordingConfig,
  PlaybackConfig,
  RecordingResult,
  PermissionResult,
} from "./types";

export type {
  AudioChunkEvent,
  AudioErrorEvent,
  RecordingConfig,
  PlaybackConfig,
  RecordingResult,
  PermissionResult,
};

// Re-export the native module for advanced usage
export { AudioStreamModule };

type EventSubscription = { remove(): void };

const AUDIO_CHUNK_EVENT = "onAudioChunk";
const ERROR_EVENT = "onError";
const PLAYBACK_COMPLETE_EVENT = "onPlaybackComplete";

export interface UseAudioStreamOptions {
  onError?: (error: AudioErrorEvent) => void;
}

export function useAudioStream(options: UseAudioStreamOptions = {}) {
  const [isRecording, setIsRecording] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const subscriptionRef = useRef<EventSubscription | null>(null);
  const errorSubscriptionRef = useRef<EventSubscription | null>(null);
  const playbackSubscriptionRef = useRef<EventSubscription | null>(null);
  const callbackRef = useRef<((event: AudioChunkEvent) => void) | null>(null);
  const errorCallbackRef = useRef<((error: AudioErrorEvent) => void) | null>(
    options.onError ?? null
  );
  const playbackCompleteRef = useRef<(() => void) | null>(null);
  const preparedRef = useRef(false);

  // Update error callback ref when options change
  useEffect(() => {
    errorCallbackRef.current = options.onError ?? null;
  }, [options.onError]);

  // Set up global error listener
  useEffect(() => {
    errorSubscriptionRef.current = AudioStreamModule.addListener(
      ERROR_EVENT,
      (event: AudioErrorEvent) => {
        errorCallbackRef.current?.(event);
      }
    );

    return () => {
      errorSubscriptionRef.current?.remove();
    };
  }, []);

  const prepare = useCallback(async () => {
    if (preparedRef.current) return;
    try {
      await AudioStreamModule.prepare();
      preparedRef.current = true;
    } catch {
      preparedRef.current = false;
    }
  }, []);

  const startRecording = useCallback(
    async (
      config: RecordingConfig = {},
      onAudioChunk: (event: AudioChunkEvent) => void
    ): Promise<RecordingResult> => {
      callbackRef.current = onAudioChunk;

      subscriptionRef.current = AudioStreamModule.addListener(
        AUDIO_CHUNK_EVENT,
        (event: AudioChunkEvent) => {
          callbackRef.current?.(event);
        }
      );

      const result = await AudioStreamModule.startRecording({
        sampleRate: config.sampleRate ?? 16000,
        channels: config.channels ?? 1,
        intervalMs: config.intervalMs ?? 50,
      });

      setIsRecording(true);
      return result;
    },
    []
  );

  const stopRecording = useCallback(async () => {
    await AudioStreamModule.stopRecording();
    subscriptionRef.current?.remove();
    subscriptionRef.current = null;
    callbackRef.current = null;
    setIsRecording(false);
  }, []);

  const requestPermissions = useCallback(async (): Promise<PermissionResult> => {
    const result = await AudioStreamModule.requestPermissions();
    if (result.granted) {
      prepare();
    }
    return result;
  }, [prepare]);

  const startPlayback = useCallback(
    async (config: PlaybackConfig = {}, onPlaybackComplete?: () => void) => {
      playbackCompleteRef.current = onPlaybackComplete ?? null;

      playbackSubscriptionRef.current = AudioStreamModule.addListener(
        PLAYBACK_COMPLETE_EVENT,
        () => {
          setIsPlaying(false);
          playbackCompleteRef.current?.();
          playbackSubscriptionRef.current?.remove();
          playbackSubscriptionRef.current = null;
        }
      );

      await AudioStreamModule.startPlayback({
        sampleRate: config.sampleRate ?? 24000,
        channels: config.channels ?? 1,
      });

      setIsPlaying(true);
    },
    []
  );

  const playChunk = useCallback(async (base64Data: string) => {
    await AudioStreamModule.playChunk(base64Data);
  }, []);

  const endPlayback = useCallback(async () => {
    await AudioStreamModule.endPlayback();
  }, []);

  const stopPlayback = useCallback(async () => {
    await AudioStreamModule.stopPlayback();
    setIsPlaying(false);
    playbackSubscriptionRef.current?.remove();
    playbackSubscriptionRef.current = null;
    playbackCompleteRef.current = null;
  }, []);

  useEffect(() => {
    return () => {
      subscriptionRef.current?.remove();
      playbackSubscriptionRef.current?.remove();
    };
  }, []);

  return {
    isRecording,
    isPlaying,
    prepare,
    startRecording,
    stopRecording,
    requestPermissions,
    startPlayback,
    playChunk,
    endPlayback,
    stopPlayback,
  };
}
