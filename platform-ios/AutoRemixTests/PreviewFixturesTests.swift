import CryptoKit
import SwiftUI
import UIKit
import XCTest
@testable import AutoRemix

@MainActor
final class PreviewFixturesTests: XCTestCase {
    func testNativeAudioBridgeRequiresStereoOutput() {
        XCTAssertNil(NativeAudioBridge(sampleRate: 48_000, channels: 0, ringCapacityFrames: 64))
        XCTAssertNil(NativeAudioBridge(sampleRate: 48_000, channels: 1, ringCapacityFrames: 64))
        XCTAssertNotNil(NativeAudioBridge(sampleRate: 48_000, channels: 2, ringCapacityFrames: 64))
        XCTAssertNil(NativeAudioBridge(sampleRate: 48_000, channels: 3, ringCapacityFrames: 64))
    }

    func testFixtureIdentityIsStable() {
        XCTAssertEqual(PreviewFixtures.snapshotCases.map { $0.name }, ["preparing", "transitioning"])
        XCTAssertEqual(PreviewFixtures.preparing.current?.id, "preview-a")
        XCTAssertEqual(PreviewFixtures.preparing.next?.id, "preview-b")
        XCTAssertEqual(PreviewFixtures.waveform.count, 72)
    }

    func testPreparingSnapshotRendersDeterministically() throws {
        let first = try render(PreviewFixtures.preparing)
        let second = try render(PreviewFixtures.preparing)
        XCTAssertEqual(Data(SHA256.hash(data: first)), Data(SHA256.hash(data: second)))
        XCTAssertGreaterThan(first.count, 10_000)
    }

    func testFeedbackFixtureMatchesVisibleState() {
        let controller = PlaybackController.preview(PreviewFixtures.transitioning)
        XCTAssertEqual(controller.snapshot.feedback, .liked)
        controller.like()
        XCTAssertEqual(controller.snapshot.feedback, .none)
    }

    private func render(_ snapshot: PlaybackSnapshot) throws -> Data {
        let content = NowPlayingScreen(
            snapshot: snapshot,
            platformMessage: nil,
            actions: .preview
        )
        .frame(width: 390, height: 844)

        let renderer = ImageRenderer(content: content)
        renderer.proposedSize = ProposedViewSize(width: 390, height: 844)
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.uiImage)
        return try XCTUnwrap(image.pngData())
    }
}
