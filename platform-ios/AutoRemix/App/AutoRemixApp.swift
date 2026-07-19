import SwiftUI

@main
struct AutoRemixApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var playback = PlaybackController.live()

    var body: some Scene {
        WindowGroup {
            ContentView(controller: playback)
                .onAppear {
                    playback.activate()
                }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                try? BackgroundAnalysisScheduler.shared.schedule(
                    earliestBeginDate: Date(timeIntervalSinceNow: 15 * 60)
                )
            }
        }
    }
}
