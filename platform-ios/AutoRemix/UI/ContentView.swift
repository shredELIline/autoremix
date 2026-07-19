import SwiftUI

struct ContentView: View {
    @ObservedObject var controller: PlaybackController

    var body: some View {
        NowPlayingScreen(
            snapshot: controller.snapshot,
            platformMessage: controller.platformMessage,
            actions: PlaybackActions(
                togglePlayback: controller.togglePlayback,
                seek: controller.seek(progress:),
                previous: controller.previous,
                next: controller.next,
                like: controller.like,
                dislike: controller.dislike,
                setStorageBudget: controller.setStorageBudget(gigabytes:)
            )
        )
    }
}

struct PlaybackActions {
    let togglePlayback: () -> Void
    let seek: (Double) -> Void
    let previous: () -> Void
    let next: () -> Void
    let like: () -> Void
    let dislike: () -> Void
    let setStorageBudget: (Double) -> Void

    static let preview = PlaybackActions(
        togglePlayback: {},
        seek: { _ in },
        previous: {},
        next: {},
        like: {},
        dislike: {},
        setStorageBudget: { _ in }
    )
}

struct NowPlayingScreen: View {
    let snapshot: PlaybackSnapshot
    let platformMessage: String?
    let actions: PlaybackActions

    @State private var settingsPresented = false

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(hex: 0x090A12), Color(hex: 0x101426), Color(hex: 0x07080E)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 22) {
                    header
                    if let track = snapshot.current {
                        artwork(for: track)
                        trackIdentity(track)
                        progressSection
                        transitionCard
                        transportControls
                        nextCard
                        queueSection
                    } else {
                        emptyState
                    }
                    if let platformMessage {
                        Text(platformMessage)
                            .font(.footnote)
                            .foregroundStyle(Color.orange.opacity(0.9))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .background(cardBackground)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)
                .padding(.bottom, 34)
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $settingsPresented) {
            StorageSettingsSheet(
                storageBudgetGB: snapshot.storageBudgetGB,
                cacheUsedGB: snapshot.cacheUsedGB,
                onBudgetChanged: actions.setStorageBudget
            )
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("AUTOREMIX")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .tracking(2.2)
                    .foregroundStyle(Color(hex: 0xA78BFA))
                Text("Now Playing")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
            }
            Spacer()
            Button {
                settingsPresented = true
            } label: {
                Image(systemName: "externaldrive.badge.gearshape")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color.white.opacity(0.08)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Storage settings")
        }
    }

    private func artwork(for track: TrackPresentation) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(hex: track.accentHex).opacity(0.96),
                            Color(hex: 0x18213D),
                            Color(hex: 0x0A0C15)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Circle()
                .fill(Color.white.opacity(0.10))
                .frame(width: 176, height: 176)
                .blur(radius: 1)
            Circle()
                .stroke(Color.white.opacity(0.22), lineWidth: 1)
                .frame(width: 112, height: 112)
            Image(systemName: "waveform.path")
                .font(.system(size: 42, weight: .medium))
                .foregroundStyle(.white.opacity(0.92))
        }
        .aspectRatio(1, contentMode: .fit)
        .shadow(color: Color(hex: track.accentHex).opacity(0.28), radius: 30, y: 16)
        .accessibilityHidden(true)
    }

    private func trackIdentity(_ track: TrackPresentation) -> some View {
        VStack(spacing: 7) {
            Text(track.title)
                .font(.system(size: 25, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.center)
            Text(track.artist)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.white.opacity(0.60))
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    private var progressSection: some View {
        VStack(spacing: 9) {
            WaveformProgressView(
                waveform: snapshot.waveform,
                progress: snapshot.normalizedProgress,
                accent: Color(hex: snapshot.current?.accentHex ?? 0x8B5CF6),
                onSeek: actions.seek
            )
            HStack {
                Text(format(snapshot.elapsed))
                Spacer()
                Text("−\(format(max(0, snapshot.duration - snapshot.elapsed)))")
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(Color.white.opacity(0.55))
        }
    }

    private var transitionCard: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(spacing: 9) {
                Image(systemName: transitionSymbol)
                    .foregroundStyle(Color(hex: 0x5EEAD4))
                Text(snapshot.transition.label)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Spacer()
                Text("\(Int(snapshot.transition.progress * 100))%")
                    .font(.caption.monospacedDigit().weight(.semibold))
                    .foregroundStyle(Color.white.opacity(0.55))
            }
            ProgressView(value: snapshot.transition.progress)
                .tint(Color(hex: 0x5EEAD4))
        }
        .padding(15)
        .background(cardBackground)
        .accessibilityElement(children: .combine)
    }

    private var transitionSymbol: String {
        switch snapshot.transition {
        case .transitioning:
            return "waveform.path.ecg"
        case .ready:
            return "checkmark.circle.fill"
        case .fallback:
            return "shield.lefthalf.filled"
        case .idle, .preparing:
            return "sparkles"
        }
    }

    private var transportControls: some View {
        HStack(spacing: 13) {
            transportButton("backward.end.fill", label: "Back", action: actions.previous)
            transportButton(
                "hand.thumbsdown.fill",
                label: "Dislike",
                active: snapshot.feedback == .disliked,
                action: actions.dislike
            )
            Button(action: actions.togglePlayback) {
                Image(systemName: snapshot.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 25, weight: .bold))
                    .foregroundStyle(Color(hex: 0x090A12))
                    .frame(width: 66, height: 66)
                    .background(Circle().fill(Color.white))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(snapshot.isPlaying ? "Pause" : "Play")
            transportButton(
                "hand.thumbsup.fill",
                label: "Like",
                active: snapshot.feedback == .liked,
                action: actions.like
            )
            transportButton("forward.end.fill", label: "Next", action: actions.next)
        }
        .frame(maxWidth: .infinity)
    }

    private func transportButton(
        _ symbol: String,
        label: String,
        active: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(active ? Color(hex: 0x5EEAD4) : Color.white.opacity(0.76))
                .frame(width: 46, height: 46)
                .background(Circle().fill(active ? Color(hex: 0x134E4A).opacity(0.65) : Color.white.opacity(0.07)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var nextCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("NEXT")
                .font(.caption2.weight(.bold))
                .tracking(1.6)
                .foregroundStyle(Color.white.opacity(0.42))
            if let next = snapshot.next {
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Color(hex: next.accentHex).opacity(0.75))
                        .frame(width: 46, height: 46)
                        .overlay(Image(systemName: "waveform").foregroundStyle(.white))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(next.title).font(.subheadline.weight(.bold)).foregroundStyle(.white)
                        Text(next.artist).font(.caption).foregroundStyle(Color.white.opacity(0.52))
                    }
                    Spacer()
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .foregroundStyle(Color(hex: 0xA78BFA))
                }
            } else {
                Text("Queue another local track to prepare a bridge.")
                    .font(.subheadline)
                    .foregroundStyle(Color.white.opacity(0.55))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(15)
        .background(cardBackground)
    }

    private var queueSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Queue")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(.white)
                Spacer()
                Text("\(snapshot.queue.count) tracks")
                    .font(.caption)
                    .foregroundStyle(Color.white.opacity(0.46))
            }
            ForEach(Array(snapshot.queue.enumerated()), id: \.element.id) { index, track in
                HStack(spacing: 12) {
                    Text("\(index + 1)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Color.white.opacity(0.36))
                        .frame(width: 18)
                    Circle()
                        .fill(Color(hex: track.accentHex))
                        .frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title).font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                        Text(track.artist).font(.caption).foregroundStyle(Color.white.opacity(0.48))
                    }
                    Spacer()
                    Text(format(track.duration))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Color.white.opacity(0.42))
                }
                if index < snapshot.queue.count - 1 {
                    Divider().overlay(Color.white.opacity(0.07))
                }
            }
        }
        .padding(15)
        .background(cardBackground)
    }

    private var emptyState: some View {
        VStack(spacing: 18) {
            Image(systemName: "music.note.list")
                .font(.system(size: 43, weight: .light))
                .foregroundStyle(Color(hex: 0xA78BFA))
            Text("No local playback session")
                .font(.title3.weight(.bold))
                .foregroundStyle(.white)
            Text("Your analyzed local queue will appear here. Network access is not required for playback.")
                .font(.subheadline)
                .foregroundStyle(Color.white.opacity(0.56))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 54)
        .padding(.horizontal, 24)
        .background(cardBackground)
    }

    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(Color.white.opacity(0.055))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(Color.white.opacity(0.08), lineWidth: 1)
            )
    }

    private func format(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite else { return "0:00" }
        let total = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

private struct WaveformProgressView: View {
    let waveform: [Double]
    let progress: Double
    let accent: Color
    let onSeek: (Double) -> Void

    var body: some View {
        GeometryReader { proxy in
            HStack(alignment: .center, spacing: 2) {
                ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                    Capsule(style: .continuous)
                        .fill(Double(index) / Double(max(values.count - 1, 1)) <= progress
                              ? accent
                              : Color.white.opacity(0.16))
                        .frame(maxWidth: .infinity)
                        .frame(height: max(3, proxy.size.height * value))
                }
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        onSeek(min(max(value.location.x / max(proxy.size.width, 1), 0), 1))
                    }
            )
        }
        .frame(height: 54)
        .accessibilityLabel("Track progress")
        .accessibilityValue("\(Int(progress * 100)) percent")
    }

    private var values: [Double] {
        waveform.isEmpty ? Array(repeating: 0.18, count: 48) : waveform
    }
}

private struct StorageSettingsSheet: View {
    let cacheUsedGB: Double
    let onBudgetChanged: (Double) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var budget: Double

    init(storageBudgetGB: Double, cacheUsedGB: Double, onBudgetChanged: @escaping (Double) -> Void) {
        self.cacheUsedGB = cacheUsedGB
        self.onBudgetChanged = onBudgetChanged
        _budget = State(initialValue: storageBudgetGB)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Storage") {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Cache budget")
                            Spacer()
                            Text("\(budget, specifier: "%.0f") GB")
                                .foregroundStyle(.secondary)
                        }
                        Slider(value: $budget, in: 1...24, step: 1)
                            .onChange(of: budget, perform: onBudgetChanged)
                        Text("Currently used: \(cacheUsedGB, specifier: "%.1f") GB")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("Quality") {
                    LabeledContent("Device tier", value: "Conservative")
                    Text("This scaffold uses conservative deterministic settings until device capability measurement is connected.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("Privacy") {
                    Label("Analysis and playback stay on this device.", systemImage: "lock.shield")
                }
            }
            .navigationTitle("Playback Storage")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            ContentView(controller: .preview(PreviewFixtures.preparing))
                .previewDisplayName("Preparing")
            ContentView(controller: .preview(PreviewFixtures.transitioning))
                .previewDisplayName("Transition")
        }
    }
}
