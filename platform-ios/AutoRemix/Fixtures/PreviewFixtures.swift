import Foundation

enum PreviewFixtures {
    static let waveform: [Double] = (0..<72).map { index in
        let carrier = 0.42 + 0.28 * sin(Double(index) * 0.61)
        let pulse = 0.18 * abs(sin(Double(index) * 0.17))
        return min(max(carrier + pulse, 0.08), 0.96)
    }

    static let current = TrackPresentation(
        id: "preview-a",
        title: "Midnight Lines",
        artist: "Local Preview Fixture",
        duration: 236,
        accentHex: 0x8B5CF6
    )

    static let next = TrackPresentation(
        id: "preview-b",
        title: "Glass Horizon",
        artist: "Local Preview Fixture",
        duration: 218,
        accentHex: 0x2DD4BF
    )

    static let queue = [
        next,
        TrackPresentation(
            id: "preview-c",
            title: "Soft Machinery",
            artist: "Local Preview Fixture",
            duration: 251,
            accentHex: 0x60A5FA
        ),
        TrackPresentation(
            id: "preview-d",
            title: "Afterimage",
            artist: "Local Preview Fixture",
            duration: 203,
            accentHex: 0xF472B6
        )
    ]

    static let preparing = PlaybackSnapshot(
        current: current,
        next: next,
        queue: queue,
        elapsed: 91,
        isPlaying: true,
        transition: .preparing(progress: 0.64),
        feedback: .none,
        waveform: waveform,
        storageBudgetGB: 6,
        cacheUsedGB: 1.7
    )

    static let transitioning = PlaybackSnapshot(
        current: current,
        next: next,
        queue: queue,
        elapsed: 174,
        isPlaying: true,
        transition: .transitioning(progress: 0.42),
        feedback: .liked,
        waveform: waveform,
        storageBudgetGB: 6,
        cacheUsedGB: 2.1
    )

    static let snapshotCases: [(name: String, snapshot: PlaybackSnapshot)] = [
        ("preparing", preparing),
        ("transitioning", transitioning)
    ]
}
