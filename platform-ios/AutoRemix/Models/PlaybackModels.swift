import Foundation

struct TrackPresentation: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let artist: String
    let duration: TimeInterval
    let accentHex: UInt32
}

enum TransitionPhase: Equatable, Sendable {
    case idle
    case preparing(progress: Double)
    case ready
    case transitioning(progress: Double)
    case fallback(reason: String)

    var progress: Double {
        switch self {
        case .idle:
            return 0
        case let .preparing(progress), let .transitioning(progress):
            return min(max(progress, 0), 1)
        case .ready:
            return 1
        case .fallback:
            return 0
        }
    }

    var label: String {
        switch self {
        case .idle:
            return "Waiting for a queued track"
        case .preparing:
            return "Preparing seamless transition"
        case .ready:
            return "Seamless transition ready"
        case .transitioning:
            return "Transition in progress"
        case let .fallback(reason):
            return reason
        }
    }
}

enum FeedbackState: Equatable, Sendable {
    case none
    case liked
    case disliked
}

struct PlaybackSnapshot: Equatable, Sendable {
    var current: TrackPresentation?
    var next: TrackPresentation?
    var queue: [TrackPresentation]
    var elapsed: TimeInterval
    var isPlaying: Bool
    var transition: TransitionPhase
    var feedback: FeedbackState
    var waveform: [Double]
    var storageBudgetGB: Double
    var cacheUsedGB: Double

    var duration: TimeInterval {
        max(current?.duration ?? 0, 0)
    }

    var normalizedProgress: Double {
        guard duration > 0 else { return 0 }
        return min(max(elapsed / duration, 0), 1)
    }

    static let empty = PlaybackSnapshot(
        current: nil,
        next: nil,
        queue: [],
        elapsed: 0,
        isPlaying: false,
        transition: .idle,
        feedback: .none,
        waveform: [],
        storageBudgetGB: 4,
        cacheUsedGB: 0
    )
}

enum PlaybackCommand: Equatable, Sendable {
    case next
    case previous
    case like(trackID: String)
    case dislike(trackID: String)
    case seek(progress: Double)
}
