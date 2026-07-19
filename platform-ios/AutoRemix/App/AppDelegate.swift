import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        BackgroundAnalysisScheduler.shared.register {
            try await BackgroundAnalysisQueue.shared.runPending()
        }
        return true
    }
}
