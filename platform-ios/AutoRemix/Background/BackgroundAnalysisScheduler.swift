import BackgroundTasks
import Foundation

final class BackgroundAnalysisScheduler {
    static let shared = BackgroundAnalysisScheduler()
    static let taskIdentifier = "com.alexey.autoremix.analysis"

    typealias WorkHandler = @Sendable () async throws -> Void

    private let lock = NSLock()
    private var workHandler: WorkHandler?

    private init() {}

    func register(workHandler: @escaping WorkHandler) {
        lock.lock()
        self.workHandler = workHandler
        lock.unlock()

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.taskIdentifier,
            using: nil
        ) { [weak self] task in
            guard let task = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self?.handle(task)
        }
    }

    func schedule(earliestBeginDate: Date? = nil) throws {
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.earliestBeginDate = earliestBeginDate
        request.requiresExternalPower = false
        request.requiresNetworkConnectivity = false
        try BGTaskScheduler.shared.submit(request)
    }

    func cancelPending() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
    }

    private func handle(_ processingTask: BGProcessingTask) {
        lock.lock()
        let handler = workHandler
        lock.unlock()

        guard let handler else {
            processingTask.setTaskCompleted(success: true)
            return
        }

        let operation = Task {
            try Task.checkCancellation()
            try await handler()
        }
        processingTask.expirationHandler = {
            operation.cancel()
        }
        Task {
            do {
                try await operation.value
                processingTask.setTaskCompleted(success: true)
            } catch {
                processingTask.setTaskCompleted(success: false)
            }
        }
    }
}

actor BackgroundAnalysisQueue {
    static let shared = BackgroundAnalysisQueue()

    private var pending: [BackgroundAnalysisScheduler.WorkHandler] = []

    func enqueue(_ work: @escaping BackgroundAnalysisScheduler.WorkHandler) {
        pending.append(work)
    }

    func runPending() async throws {
        while !pending.isEmpty {
            try Task.checkCancellation()
            let work = pending.removeFirst()
            try await work()
        }
    }
}
