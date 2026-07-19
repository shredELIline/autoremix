import AVFoundation
import Foundation

@MainActor
final class AudioEngineController {
    enum AudioEngineError: LocalizedError {
        case bridgeCreationFailed
        case unsupportedFormat

        var errorDescription: String? {
            switch self {
            case .bridgeCreationFailed:
                return "The shared audio core could not be created."
            case .unsupportedFormat:
                return "The device does not expose stereo Float32 output."
            }
        }
    }

    let sampleRate: Double
    let channels: AVAudioChannelCount

    private let engine = AVAudioEngine()
    private let session = AVAudioSession.sharedInstance()
    private let bridge: NativeAudioBridge
    private var sourceNode: AVAudioSourceNode?
    private var notificationTokens: [NSObjectProtocol] = []
    private var configured = false

    init(sampleRate: Double = 48_000, channels: AVAudioChannelCount = 2) throws {
        self.sampleRate = sampleRate
        self.channels = channels
        guard let bridge = NativeAudioBridge(
            sampleRate: sampleRate,
            channels: UInt32(channels),
            ringCapacityFrames: UInt32(sampleRate * 30)
        ) else {
            throw AudioEngineError.bridgeCreationFailed
        }
        self.bridge = bridge
        installAudioSessionObservers()
    }

    deinit {
        engine.stop()
        try? bridge.setPaused(true)
        for token in notificationTokens {
            NotificationCenter.default.removeObserver(token)
        }
    }

    var renderedFrame: UInt64 {
        bridge.renderedFrame
    }

    var availableFrames: UInt32 {
        bridge.availableFrames
    }

    var isRunning: Bool {
        engine.isRunning
    }

    func start() throws {
        try configureIfNeeded()
        try session.setActive(true)
        try bridge.setPaused(false)
        if !engine.isRunning {
            try engine.start()
        }
    }

    func pause() throws {
        try bridge.setPaused(true)
        engine.pause()
    }

    func resume() throws {
        try start()
    }

    func stop() {
        try? bridge.setPaused(true)
        engine.stop()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }

    func seek(toFrame targetFrame: UInt64) throws {
        let shouldResume = engine.isRunning
        if shouldResume {
            engine.pause()
        }
        try bridge.seek(toFrame: targetFrame)
        if shouldResume {
            try engine.start()
        }
    }

    @discardableResult
    func enqueue(interleaved samples: [Float]) -> UInt32 {
        samples.withUnsafeBufferPointer { buffer in
            guard let baseAddress = buffer.baseAddress else { return 0 }
            return bridge.enqueue(
                interleavedSamples: baseAddress,
                sampleCount: UInt32(min(buffer.count, Int(UInt32.max)))
            )
        }
    }

    private func configureIfNeeded() throws {
        guard !configured else { return }
        try session.setCategory(
            .playback,
            mode: .default,
            options: [.allowAirPlay, .allowBluetoothA2DP]
        )
        try session.setPreferredSampleRate(sampleRate)

        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: channels,
            interleaved: false
        ) else {
            throw AudioEngineError.unsupportedFormat
        }

        let bridge = self.bridge
        let sourceNode = AVAudioSourceNode(format: format) { _, _, frameCount, bufferList in
            bridge.render(into: bufferList, frameCount: frameCount)
        }
        engine.attach(sourceNode)
        engine.connect(sourceNode, to: engine.mainMixerNode, format: format)
        self.sourceNode = sourceNode
        configured = true
    }

    private func installAudioSessionObservers() {
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: session,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor in
                self?.handleInterruption(notification)
            }
        })
        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: session,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor in
                self?.handleRouteChange(notification)
            }
        })
    }

    private func handleInterruption(_ notification: Notification) {
        guard
            let rawType = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: rawType)
        else { return }

        switch type {
        case .began:
            try? pause()
        case .ended:
            let rawOptions = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            if AVAudioSession.InterruptionOptions(rawValue: rawOptions).contains(.shouldResume) {
                try? resume()
            }
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ notification: Notification) {
        guard
            let rawReason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
            AVAudioSession.RouteChangeReason(rawValue: rawReason) == .oldDeviceUnavailable
        else { return }
        try? pause()
    }
}
