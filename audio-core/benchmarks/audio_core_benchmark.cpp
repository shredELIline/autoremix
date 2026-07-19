#include "autoremix/audio_core.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <string>
#include <vector>

namespace ar = autoremix::audio;

namespace {

constexpr std::uint32_t kSampleRate = 48'000U;
constexpr std::uint32_t kChannels = 2U;
constexpr std::uint64_t kFrames = 4U * kSampleRate;
constexpr std::size_t kIterations = 7U;

struct OwnedStem final {
  ar::StemInput view{};
  std::vector<float> source_a;
  std::vector<float> source_b;
  std::vector<float> generated;
};

OwnedStem make_stem(std::uint32_t id, ar::StemRole role, double frequency) {
  OwnedStem stem;
  const auto samples = static_cast<std::size_t>(kFrames * kChannels);
  stem.source_a.resize(samples);
  stem.source_b.resize(samples);
  if (role != ar::StemRole::Vocal && role != ar::StemRole::BackingVocal) {
    stem.generated.resize(samples);
  }
  for (std::uint64_t frame = 0; frame < kFrames; ++frame) {
    const auto time = static_cast<double>(frame) / kSampleRate;
    const auto a = static_cast<float>(0.08 * std::sin(2.0 * 3.141592653589793 *
                                                     frequency * time));
    const auto b = static_cast<float>(0.08 * std::sin(2.0 * 3.141592653589793 *
                                                     frequency * 1.059463 * time));
    const auto texture = static_cast<float>(
        0.025 * std::sin(2.0 * 3.141592653589793 * frequency * 0.5 * time));
    const auto offset = static_cast<std::size_t>(frame * kChannels);
    stem.source_a[offset] = stem.source_a[offset + 1U] = a;
    stem.source_b[offset] = stem.source_b[offset + 1U] = b;
    if (!stem.generated.empty()) {
      stem.generated[offset] = stem.generated[offset + 1U] = texture;
    }
  }
  stem.view.features = {id, role, true, true, 0.92F, 0.91F, 0.88F, 0.9F,
                        role == ar::StemRole::Vocal ? 0.9F : 0.35F};
  stem.view.source_a = {stem.source_a.data(), kFrames, kChannels, kSampleRate};
  stem.view.source_b = {stem.source_b.data(), kFrames, kChannels, kSampleRate};
  if (!stem.generated.empty()) {
    stem.view.generated = {stem.generated.data(), kFrames, kChannels, kSampleRate};
  }
  return stem;
}

std::vector<ar::StemInput> views(const std::vector<OwnedStem> &owned) {
  std::vector<ar::StemInput> result;
  result.reserve(owned.size());
  for (const auto &stem : owned) {
    auto view = stem.view;
    view.source_a.interleaved = stem.source_a.data();
    view.source_b.interleaved = stem.source_b.data();
    if (!stem.generated.empty()) {
      view.generated.interleaved = stem.generated.data();
    }
    result.push_back(view);
  }
  return result;
}

} // namespace

int main() {
  std::vector<OwnedStem> owned;
  owned.push_back(make_stem(1U, ar::StemRole::Vocal, 220.0));
  owned.push_back(make_stem(2U, ar::StemRole::Drums, 82.0));
  owned.push_back(make_stem(3U, ar::StemRole::Bass, 55.0));
  owned.push_back(make_stem(4U, ar::StemRole::Harmony, 330.0));

  ar::BridgeRequest request;
  request.sample_rate = kSampleRate;
  request.bpm_a = 121.0;
  request.bpm_b = 126.0;
  request.duration_frames = kFrames;
  request.harmonic_distance = 1.0;
  request.stem_conflict_score = 0.08;
  request.stems = views(owned);
  request.capability = {0.25, 512ULL * 1024ULL * 1024ULL, 8U, 0.9F, 1.0F,
                        false};

  std::vector<double> elapsed_ms;
  elapsed_ms.reserve(kIterations);
  ar::BridgeOutcome outcome;
  for (std::size_t iteration = 0; iteration < kIterations; ++iteration) {
    const auto started = std::chrono::steady_clock::now();
    outcome = ar::BridgeEngine{}.render(request);
    const auto finished = std::chrono::steady_clock::now();
    elapsed_ms.push_back(
        std::chrono::duration<double, std::milli>(finished - started).count());
    if (outcome.render.cancelled || outcome.render.interleaved.empty()) {
      std::cerr << "benchmark render failed\n";
      return EXIT_FAILURE;
    }
  }

  std::sort(elapsed_ms.begin(), elapsed_ms.end());
  const auto median_ms = elapsed_ms[elapsed_ms.size() / 2U];
  const auto audio_ms = 1000.0 * static_cast<double>(kFrames) / kSampleRate;
  const auto peak = std::accumulate(
      outcome.render.interleaved.begin(), outcome.render.interleaved.end(),
      0.0F, [](float value, float sample) {
        return std::max(value, std::abs(sample));
      });

  std::cout << std::fixed << std::setprecision(4)
            << "{\n"
            << "  \"sample_rate_hz\": " << kSampleRate << ",\n"
            << "  \"channels\": " << kChannels << ",\n"
            << "  \"audio_duration_seconds\": " << audio_ms / 1000.0 << ",\n"
            << "  \"iterations\": " << kIterations << ",\n"
            << "  \"median_render_ms\": " << median_ms << ",\n"
            << "  \"median_realtime_factor\": " << median_ms / audio_ms << ",\n"
            << "  \"output_samples\": " << outcome.render.interleaved.size()
            << ",\n"
            << "  \"output_peak\": " << peak << ",\n"
            << "  \"fallback_stage\": " << static_cast<unsigned>(outcome.stage)
            << "\n"
            << "}\n";
  return EXIT_SUCCESS;
}
