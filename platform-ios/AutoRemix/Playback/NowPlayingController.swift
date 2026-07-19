import MediaPlayer

@MainActor
final class NowPlayingController {
    struct Handlers {
        let play: () -> Void
        let pause: () -> Void
        let next: () -> Void
        let previous: () -> Void
        let seek: (TimeInterval) -> Void
        let like: () -> Void
        let dislike: () -> Void
    }

    private let commandCenter = MPRemoteCommandCenter.shared()
    private var handlers: Handlers?

    func install(handlers: Handlers) {
        self.handlers = handlers
        removeTargets()

        commandCenter.playCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor in self?.handlers?.play() }
            return .success
        }
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor in self?.handlers?.pause() }
            return .success
        }
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor in self?.handlers?.next() }
            return .success
        }
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor in self?.handlers?.previous() }
            return .success
        }
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard self != nil,
                  let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            let position = event.positionTime
            Task { @MainActor in self?.handlers?.seek(position) }
            return .success
        }
        commandCenter.likeCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor in self?.handlers?.like() }
            return .success
        }
        commandCenter.dislikeCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor in self?.handlers?.dislike() }
            return .success
        }
    }

    func update(snapshot: PlaybackSnapshot) {
        guard let track = snapshot.current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyPlaybackDuration: track.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: snapshot.elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: snapshot.isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue
        ]
    }

    func clear() {
        handlers = nil
        removeTargets()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private func removeTargets() {
        commandCenter.playCommand.removeTarget(nil)
        commandCenter.pauseCommand.removeTarget(nil)
        commandCenter.nextTrackCommand.removeTarget(nil)
        commandCenter.previousTrackCommand.removeTarget(nil)
        commandCenter.changePlaybackPositionCommand.removeTarget(nil)
        commandCenter.likeCommand.removeTarget(nil)
        commandCenter.dislikeCommand.removeTarget(nil)
    }
}
