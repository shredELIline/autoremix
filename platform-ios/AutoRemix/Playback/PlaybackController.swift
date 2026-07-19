import Foundation

@MainActor
final class PlaybackController: ObservableObject {
    typealias CommandSink = @MainActor (PlaybackCommand) -> Void

    @Published private(set) var snapshot: PlaybackSnapshot
    @Published private(set) var platformMessage: String?

    private let audio: AudioEngineController?
    private let nowPlaying: NowPlayingController?
    private let commandSink: CommandSink?
    private var progressTimer: Timer?
    private var activated = false

    private init(
        snapshot: PlaybackSnapshot,
        audio: AudioEngineController?,
        nowPlaying: NowPlayingController?,
        commandSink: CommandSink?,
        platformMessage: String? = nil
    ) {
        self.snapshot = snapshot
        self.audio = audio
        self.nowPlaying = nowPlaying
        self.commandSink = commandSink
        self.platformMessage = platformMessage
    }

    static func live(commandSink: CommandSink? = nil) -> PlaybackController {
        do {
            return PlaybackController(
                snapshot: .empty,
                audio: try AudioEngineController(),
                nowPlaying: NowPlayingController(),
                commandSink: commandSink
            )
        } catch {
            return PlaybackController(
                snapshot: .empty,
                audio: nil,
                nowPlaying: NowPlayingController(),
                commandSink: commandSink,
                platformMessage: error.localizedDescription
            )
        }
    }

    static func preview(_ snapshot: PlaybackSnapshot) -> PlaybackController {
        PlaybackController(
            snapshot: snapshot,
            audio: nil,
            nowPlaying: nil,
            commandSink: nil
        )
    }

    func activate() {
        guard !activated else { return }
        activated = true
        nowPlaying?.install(handlers: .init(
            play: { [weak self] in self?.play() },
            pause: { [weak self] in self?.pause() },
            next: { [weak self] in self?.next() },
            previous: { [weak self] in self?.previous() },
            seek: { [weak self] time in self?.seek(to: time) },
            like: { [weak self] in self?.like() },
            dislike: { [weak self] in self?.dislike() }
        ))
        startProgressPolling()
        publishNowPlaying()
    }

    func deactivate() {
        progressTimer?.invalidate()
        progressTimer = nil
        audio?.stop()
        nowPlaying?.clear()
        activated = false
    }

    /// Integration point for the shared playback-session coordinator.
    func apply(_ newSnapshot: PlaybackSnapshot) {
        snapshot = newSnapshot
        publishNowPlaying()
    }

    func togglePlayback() {
        snapshot.isPlaying ? pause() : play()
    }

    func play() {
        guard snapshot.current != nil, let audio else { return }
        do {
            try audio.resume()
            snapshot.isPlaying = true
            platformMessage = nil
            publishNowPlaying()
        } catch {
            platformMessage = error.localizedDescription
        }
    }

    func pause() {
        guard let audio else { return }
        do {
            try audio.pause()
            snapshot.isPlaying = false
            platformMessage = nil
            publishNowPlaying()
        } catch {
            platformMessage = error.localizedDescription
        }
    }

    func seek(progress: Double) {
        guard snapshot.duration > 0 else { return }
        let bounded = min(max(progress, 0), 1)
        seek(to: snapshot.duration * bounded)
        commandSink?(.seek(progress: bounded))
    }

    func seek(to time: TimeInterval) {
        let bounded = min(max(time, 0), snapshot.duration)
        do {
            let frame = UInt64((bounded * (audio?.sampleRate ?? 48_000)).rounded())
            try audio?.seek(toFrame: frame)
            snapshot.elapsed = bounded
            platformMessage = nil
            publishNowPlaying()
        } catch {
            platformMessage = error.localizedDescription
        }
    }

    func next() {
        commandSink?(.next)
    }

    func previous() {
        commandSink?(.previous)
    }

    func like() {
        guard let trackID = snapshot.current?.id else { return }
        snapshot.feedback = snapshot.feedback == .liked ? .none : .liked
        commandSink?(.like(trackID: trackID))
    }

    func dislike() {
        guard let trackID = snapshot.current?.id else { return }
        snapshot.feedback = .disliked
        commandSink?(.dislike(trackID: trackID))
        commandSink?(.next)
    }

    func setStorageBudget(gigabytes: Double) {
        snapshot.storageBudgetGB = min(max(gigabytes, 1), 24)
    }

    private func startProgressPolling() {
        progressTimer?.invalidate()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let audio = self.audio, self.snapshot.current != nil else { return }
                self.snapshot.elapsed = min(
                    Double(audio.renderedFrame) / audio.sampleRate,
                    self.snapshot.duration
                )
                self.publishNowPlaying()
            }
        }
    }

    private func publishNowPlaying() {
        nowPlaying?.update(snapshot: snapshot)
    }
}
