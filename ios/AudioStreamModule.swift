import AudioToolbox
import AVFoundation
import ExpoModulesCore

private let AUDIO_CHUNK_EVENT = "onAudioChunk"
private let ERROR_EVENT = "onError"
private let PLAYBACK_COMPLETE_EVENT = "onPlaybackComplete"

public class AudioStreamModule: Module {
    private var audioEngine: AVAudioEngine?
    private var isRecording = false
    private var isSessionConfiguredForRecording = false
    private var startTime: Date?
    private var totalSize: Int64 = 0
    private var accumulatedData = Data()
    private var lastEmissionTime: Date?
    private var emissionIntervalSeconds: TimeInterval = 0.05
    private var targetSampleRate: Double = 16000
    private var targetChannels: Int = 1
    private var resamplerConverter: AVAudioConverter?
    private var resamplerInputFormat: AVAudioFormat?
    private var resamplerOutputFormat: AVAudioFormat?
    private var resamplerOutputBuffer: AVAudioPCMBuffer?
    private let processingQueue = DispatchQueue(label: "com.poppy.audiostream.processing", qos: .userInteractive)

    private var playbackEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var isPlaying = false
    private var playbackFormat: AVAudioFormat?
    private var buffersScheduled = 0
    private var buffersCompleted = 0
    private var playbackEnded = false
    private let playbackQueue = DispatchQueue(label: "com.poppy.audiostream.playback")

    public func definition() -> ModuleDefinition {
        Name("AudioStream")

        Events([AUDIO_CHUNK_EVENT, ERROR_EVENT, PLAYBACK_COMPLETE_EVENT])

        OnDestroy {
            self.cleanup()
            self.cleanupPlayback()
        }

        AsyncFunction("requestPermissions") { (promise: Promise) in
            switch AVAudioSession.sharedInstance().recordPermission {
            case .granted:
                promise.resolve(["granted": true, "status": "granted"])
            case .denied:
                promise.resolve(["granted": false, "status": "denied"])
            case .undetermined:
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        promise.resolve(["granted": granted, "status": granted ? "granted" : "denied"])
                    }
                }
            @unknown default:
                promise.resolve(["granted": false, "status": "unknown"])
            }
        }

        AsyncFunction("prepare") { (promise: Promise) in
            guard AVAudioSession.sharedInstance().recordPermission == .granted else {
                promise.resolve(nil)
                return
            }

            do {
                try self.configureSessionForRecording()
                promise.resolve(nil)
            } catch {
                promise.reject("PREPARE_ERROR", "Failed to prepare audio: \(error.localizedDescription)")
            }
        }

        AsyncFunction("startRecording") { (config: [String: Any], promise: Promise) in
            guard !self.isRecording else {
                promise.reject("ALREADY_RECORDING", "Recording is already in progress")
                return
            }

            self.targetSampleRate = config["sampleRate"] as? Double ?? 16000
            self.targetChannels = config["channels"] as? Int ?? 1
            let intervalMs = config["intervalMs"] as? Int ?? 50
            self.emissionIntervalSeconds = Double(intervalMs) / 1000.0

            do {
                try self.configureSessionForRecording()

                if self.audioEngine == nil {
                    self.audioEngine = AVAudioEngine()
                }

                guard let engine = self.audioEngine else {
                    promise.reject("START_ERROR", "Failed to create audio engine")
                    return
                }

                let inputNode = engine.inputNode
                let hardwareFormat = inputNode.inputFormat(forBus: 0)

                guard hardwareFormat.sampleRate > 0 else {
                    promise.reject("START_ERROR", "Invalid hardware format (no microphone?)")
                    return
                }

                inputNode.removeTap(onBus: 0)

                self.setupResampler(from: hardwareFormat)

                inputNode.installTap(onBus: 0, bufferSize: 1024, format: hardwareFormat) { [weak self] buffer, _ in
                    self?.processBuffer(buffer)
                }

                engine.prepare()
                try engine.start()

                self.isRecording = true
                self.startTime = Date()
                self.totalSize = 0
                self.accumulatedData.removeAll()
                self.lastEmissionTime = Date()

                promise.resolve([
                    "sampleRate": Int(self.targetSampleRate),
                    "channels": self.targetChannels,
                    "bitDepth": 16,
                    "mimeType": "audio/pcm",
                ])
            } catch {
                promise.reject("START_ERROR", "Failed to start recording: \(error.localizedDescription)")
            }
        }

        AsyncFunction("stopRecording") { (promise: Promise) in
            self.stopRecordingInternal()
            promise.resolve(nil)
        }

        AsyncFunction("startPlayback") { (config: [String: Any], promise: Promise) in
            self.playbackQueue.async {
                do {
                    try self.setupPlaybackEngine(config: config)
                    DispatchQueue.main.async {
                        promise.resolve(nil)
                    }
                } catch {
                    DispatchQueue.main.async {
                        promise.reject("PLAYBACK_ERROR", "Failed to start playback: \(error.localizedDescription)")
                    }
                }
            }
        }

        AsyncFunction("playChunk") { (base64Data: String, promise: Promise) in
            self.playbackQueue.async {
                do {
                    try self.decodeAndQueueChunk(base64Data: base64Data)
                    DispatchQueue.main.async {
                        promise.resolve(nil)
                    }
                } catch {
                    DispatchQueue.main.async {
                        promise.reject("CHUNK_ERROR", "Failed to play chunk: \(error.localizedDescription)")
                    }
                }
            }
        }

        AsyncFunction("endPlayback") { (promise: Promise) in
            self.playbackQueue.async {
                self.playbackEnded = true
                self.checkPlaybackComplete()
                DispatchQueue.main.async {
                    promise.resolve(nil)
                }
            }
        }

        AsyncFunction("stopPlayback") { (promise: Promise) in
            self.cleanupPlayback()
            promise.resolve(nil)
        }
    }

    private func configureSessionForRecording() throws {
        guard !isSessionConfiguredForRecording else { return }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .mixWithOthers, .defaultToSpeaker])
        try session.setActive(true)
        isSessionConfiguredForRecording = true
    }

    private func setupResampler(from inputFormat: AVAudioFormat) {
        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: targetSampleRate,
            channels: AVAudioChannelCount(targetChannels),
            interleaved: false
        ) else { return }

        if resamplerInputFormat?.sampleRate == inputFormat.sampleRate,
           resamplerInputFormat?.channelCount == inputFormat.channelCount,
           resamplerConverter != nil {
            return
        }

        resamplerConverter = AVAudioConverter(from: inputFormat, to: outputFormat)
        resamplerInputFormat = inputFormat
        resamplerOutputFormat = outputFormat

        let ratio = targetSampleRate / inputFormat.sampleRate
        let maxOutputFrames = AVAudioFrameCount(2048.0 * max(ratio, 1.0))
        resamplerOutputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: maxOutputFrames)
    }

    private func setupPlaybackEngine(config: [String: Any]) throws {
        cleanupPlayback()

        let sampleRate = config["sampleRate"] as? Double ?? 24000
        let channels = config["channels"] as? UInt32 ?? 1

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .mixWithOthers, .defaultToSpeaker])
        try session.setActive(true)

        playbackFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: channels, interleaved: false)

        playbackEngine = AVAudioEngine()
        playerNode = AVAudioPlayerNode()

        guard let engine = playbackEngine, let node = playerNode, let format = playbackFormat else {
            throw NSError(domain: "AudioStream", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create playback components"])
        }

        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)

        engine.prepare()
        try engine.start()

        node.play()
        isPlaying = true
        playbackEnded = false
        buffersScheduled = 0
        buffersCompleted = 0
    }

    private func decodeAndQueueChunk(base64Data: String) throws {
        guard let data = Data(base64Encoded: base64Data) else {
            throw NSError(domain: "AudioStream", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid base64 data"])
        }

        guard let playbackFormat else {
            throw NSError(domain: "AudioStream", code: 4, userInfo: [NSLocalizedDescriptionKey: "Playback not started"])
        }

        let frameCount = data.count / 2
        guard let buffer = AVAudioPCMBuffer(pcmFormat: playbackFormat, frameCapacity: AVAudioFrameCount(frameCount)) else {
            throw NSError(domain: "AudioStream", code: 3, userInfo: [NSLocalizedDescriptionKey: "Failed to create buffer"])
        }

        buffer.frameLength = AVAudioFrameCount(frameCount)

        guard let floatChannelData = buffer.floatChannelData else {
            throw NSError(domain: "AudioStream", code: 5, userInfo: [NSLocalizedDescriptionKey: "Failed to get channel data"])
        }

        let bytes = [UInt8](data)
        for i in 0..<frameCount {
            let low = UInt16(bytes[i * 2])
            let high = UInt16(bytes[i * 2 + 1])
            let sample = Int16(bitPattern: low | (high << 8))
            floatChannelData[0][i] = Float(sample) / 32768.0
        }

        scheduleBuffer(buffer)
    }

    private func scheduleBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let node = playerNode, isPlaying else { return }

        buffersScheduled += 1
        node.scheduleBuffer(buffer) { [weak self] in
            self?.playbackQueue.async {
                self?.buffersCompleted += 1
                self?.checkPlaybackComplete()
            }
        }
    }

    private func checkPlaybackComplete() {
        if playbackEnded, buffersCompleted >= buffersScheduled, buffersScheduled > 0 {
            DispatchQueue.main.async {
                self.sendEvent(PLAYBACK_COMPLETE_EVENT, [:])
            }
            cleanupPlayback()
        }
    }

    private func cleanupPlayback() {
        isPlaying = false
        playbackEnded = false
        buffersScheduled = 0
        buffersCompleted = 0

        if let engine = playbackEngine {
            if engine.isRunning {
                playerNode?.stop()
                engine.stop()
            }
            if let node = playerNode {
                engine.detach(node)
            }
        }

        playerNode = nil
        playbackEngine = nil
        playbackFormat = nil
    }

    private func processBuffer(_ buffer: AVAudioPCMBuffer) {
        guard isRecording else { return }

        if resamplerInputFormat?.sampleRate != buffer.format.sampleRate ||
            resamplerInputFormat?.channelCount != buffer.format.channelCount {
            setupResampler(from: buffer.format)
        }

        let bufferCopy: AVAudioPCMBuffer
        if buffer.format.sampleRate != targetSampleRate {
            guard let resampled = resampleBuffer(buffer) else { return }
            bufferCopy = resampled
        } else {
            guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else { return }
            copy.frameLength = buffer.frameLength
            if let src = buffer.floatChannelData, let dst = copy.floatChannelData {
                for ch in 0..<Int(buffer.format.channelCount) {
                    memcpy(dst[ch], src[ch], Int(buffer.frameLength) * MemoryLayout<Float>.size)
                }
            }
            bufferCopy = copy
        }

        processingQueue.async { [weak self] in
            guard let self, isRecording else { return }

            let data = convertToPCM16(bufferCopy)
            accumulatedData.append(data)

            let now = Date()
            if let lastTime = lastEmissionTime,
               now.timeIntervalSince(lastTime) >= self.emissionIntervalSeconds {
                emitAudioChunk()
                lastEmissionTime = now
            }
        }
    }

    private func emitAudioChunk() {
        guard !accumulatedData.isEmpty else { return }

        let dataToEmit = accumulatedData
        accumulatedData.removeAll()
        totalSize += Int64(dataToEmit.count)

        let position = startTime.map { Date().timeIntervalSince($0) * 1000 } ?? 0

        sendEvent(AUDIO_CHUNK_EVENT, [
            "data": dataToEmit.base64EncodedString(),
            "position": Int(position),
            "chunkSize": dataToEmit.count,
            "totalSize": totalSize,
        ])
    }

    private func resampleBuffer(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let converter = resamplerConverter,
              let outputFormat = resamplerOutputFormat,
              let outputBuffer = resamplerOutputBuffer
        else {
            return nil
        }

        outputBuffer.frameLength = 0

        var error: NSError?
        var inputConsumed = false
        converter.convert(to: outputBuffer, error: &error) { _, outStatus in
            if inputConsumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            inputConsumed = true
            outStatus.pointee = .haveData
            return buffer
        }

        guard error == nil, outputBuffer.frameLength > 0 else { return nil }

        guard let copy = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: outputBuffer.frameLength) else {
            return nil
        }
        copy.frameLength = outputBuffer.frameLength
        if let src = outputBuffer.floatChannelData, let dst = copy.floatChannelData {
            for ch in 0..<Int(outputFormat.channelCount) {
                memcpy(dst[ch], src[ch], Int(outputBuffer.frameLength) * MemoryLayout<Float>.size)
            }
        }
        return copy
    }

    private func convertToPCM16(_ buffer: AVAudioPCMBuffer) -> Data {
        guard let floatData = buffer.floatChannelData else { return Data() }

        let frameCount = Int(buffer.frameLength)
        var data = Data(capacity: frameCount * 2)

        for i in 0..<frameCount {
            let sample = floatData[0][i]
            let clampedSample = max(-1.0, min(1.0, sample))
            let int16Sample = Int16(clampedSample * Float(Int16.max))
            withUnsafeBytes(of: int16Sample.littleEndian) { data.append(contentsOf: $0) }
        }

        return data
    }

    private func stopRecordingInternal() {
        if isRecording {
            emitAudioChunk()
        }

        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        isRecording = false
        startTime = nil
    }

    private func cleanup() {
        stopRecordingInternal()
        audioEngine = nil
        resamplerConverter = nil
        resamplerInputFormat = nil
        resamplerOutputFormat = nil
        resamplerOutputBuffer = nil
        isSessionConfiguredForRecording = false
        try? AVAudioSession.sharedInstance().setActive(false)
    }

    private func emitError(code: String, message: String) {
        sendEvent(ERROR_EVENT, [
            "code": code,
            "message": message,
        ])
    }
}
