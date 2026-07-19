#include "autoremix/progressive_transition.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <iterator>
#include <limits>
#include <sstream>
#include <unordered_map>
#include <unordered_set>

namespace autoremix::audio {
namespace {

constexpr std::uint8_t kPreprocessedMagic[] = {'A', 'R', 'P', 'T'};
constexpr std::size_t kMaximumSerializedItems = 1'000'000U;
constexpr std::size_t kMinimumSerializedFragmentBytes = 184U;
constexpr std::size_t kMinimumSerializedEdgeBytes = 65U;
constexpr std::size_t kSerializedChunkJobBytes = 46U;

[[nodiscard]] const char *transition_state_name(TransitionState state) noexcept {
  switch (state) {
  case TransitionState::IDLE:
    return "IDLE";
  case TransitionState::TARGET_SELECTED:
    return "TARGET_SELECTED";
  case TransitionState::PREPARING:
    return "PREPARING";
  case TransitionState::FALLBACK_READY:
    return "FALLBACK_READY";
  case TransitionState::NEURAL_CANDIDATES_PENDING:
    return "NEURAL_CANDIDATES_PENDING";
  case TransitionState::ARMED:
    return "ARMED";
  case TransitionState::WAITING_FOR_ACTIVATION_BOUNDARY:
    return "WAITING_FOR_ACTIVATION_BOUNDARY";
  case TransitionState::TRANSITIONING:
    return "TRANSITIONING";
  case TransitionState::LANDED:
    return "LANDED";
  case TransitionState::CANCELLED:
    return "CANCELLED";
  case TransitionState::FAILED:
    return "FAILED";
  }
  return "UNKNOWN";
}

[[nodiscard]] float clamp_unit(float value) noexcept {
  if (!std::isfinite(value)) {
    return 0.0F;
  }
  return std::max(0.0F, std::min(value, 1.0F));
}

[[nodiscard]] bool finite_vector(const std::vector<float> &values) noexcept {
  return std::all_of(values.begin(), values.end(),
                     [](float value) { return std::isfinite(value); });
}

[[nodiscard]] bool contains(const std::vector<std::uint64_t> &values,
                            std::uint64_t value) noexcept {
  return std::find(values.begin(), values.end(), value) != values.end();
}

[[nodiscard]] float phase_compatibility(float left, float right) noexcept {
  if (!std::isfinite(left) || !std::isfinite(right)) {
    return 0.0F;
  }
  auto distance = std::fmod(std::abs(left - right), 1.0F);
  distance = std::min(distance, 1.0F - distance);
  return clamp_unit(1.0F - 2.0F * distance);
}

[[nodiscard]] bool valid_source(SourceKind source) noexcept {
  return source == SourceKind::SourceA || source == SourceKind::SourceB ||
         source == SourceKind::Generated;
}

[[nodiscard]] bool valid_stem_role(StemRole role) noexcept {
  return static_cast<std::uint8_t>(role) <=
         static_cast<std::uint8_t>(StemRole::Effects);
}

[[nodiscard]] bool valid_chunk_kind(ChunkKind kind) noexcept {
  switch (kind) {
  case ChunkKind::OneBar:
  case ChunkKind::TwoBars:
  case ChunkKind::FourBars:
  case ChunkKind::EightBars:
  case ChunkKind::Phrase:
    return true;
  }
  return false;
}

[[nodiscard]] bool valid_edge_kind(GraphEdgeKind kind) noexcept {
  switch (kind) {
  case GraphEdgeKind::Phrase:
  case GraphEdgeKind::Chord:
  case GraphEdgeKind::StemContinuation:
    return true;
  }
  return false;
}

[[nodiscard]] bool valid_edge_values(const ContinuationEdge &edge) noexcept {
  const float values[] = {
      edge.sample_boundary_continuity, edge.phase_continuity,
      edge.beat_alignment,             edge.chord_compatibility,
      edge.key_compatibility,          edge.timbre_compatibility,
      edge.groove_compatibility,       edge.energy_trajectory,
      edge.stem_role_fit,              edge.masking_fit,
      edge.latency_fit,                edge.cache_fit,
  };
  return std::all_of(std::begin(values), std::end(values),
                     [](float value) { return std::isfinite(value); });
}

[[nodiscard]] float descriptor_score(
    const FragmentDescriptor &fragment, const ContinuationContext &current,
    const ContinuationContext &target, std::uint32_t desired_bars,
    float graph_score) noexcept {
  auto score = 0.34F * graph_score;
  score += 0.12F * phase_compatibility(fragment.beat_phase, current.beat_phase);
  score += 0.10F * clamp_unit(1.0F - std::abs(fragment.energy - current.energy));
  score += 0.08F * clamp_unit(fragment.generation_confidence);
  score += 0.07F * clamp_unit(fragment.loopability_score);
  score += fragment.cache_available ? 0.08F : 0.0F;
  if (current.key >= 0 && fragment.key >= 0) {
    score += current.key == fragment.key ? 0.07F : -0.04F;
  }
  if (current.chord >= 0 && fragment.chord_start >= 0) {
    score += current.chord == fragment.chord_start ? 0.05F : -0.02F;
  }
  if (target.track_id != 0U && fragment.track_id == target.track_id) {
    score += 0.09F;
  }
  if (target.key >= 0 && fragment.key >= 0 && target.key == fragment.key) {
    score += 0.04F;
  }
  const auto bar_difference = static_cast<float>(
      fragment.bar_count > desired_bars ? fragment.bar_count - desired_bars
                                        : desired_bars - fragment.bar_count);
  score -= 0.01F * bar_difference;
  score -= 0.02F * std::max(0.0F, fragment.generation_latency_seconds);
  return score;
}

[[nodiscard]] std::uint64_t arrangement_identity(
    const FragmentDescriptor &fragment) noexcept {
  if (fragment.arrangement_fingerprint != 0U) {
    return fragment.arrangement_fingerprint;
  }
  auto value = fragment.audible_layer_mask;
  value ^= fragment.melodic_fingerprint + 0x9E3779B97F4A7C15ULL +
           (value << 6U) + (value >> 2U);
  value ^= fragment.harmonic_fingerprint + 0x9E3779B97F4A7C15ULL +
           (value << 6U) + (value >> 2U);
  value ^= fragment.rhythmic_fingerprint + 0x9E3779B97F4A7C15ULL +
           (value << 6U) + (value >> 2U);
  return value == 0U ? fragment.fragment_id : value;
}

[[nodiscard]] bool recent_contains(const std::vector<std::uint64_t> &values,
                                   std::uint64_t value,
                                   std::size_t window) noexcept {
  if (value == 0U || values.empty() || window == 0U) {
    return false;
  }
  const auto start = values.size() > window ? values.size() - window : 0U;
  return std::find(values.begin() + static_cast<std::ptrdiff_t>(start),
                   values.end(), value) != values.end();
}

[[nodiscard]] const ContinuationEdge *find_edge(
    const ContinuationGraph &graph, std::uint64_t from,
    std::uint64_t to) noexcept {
  const auto iterator = std::find_if(
      graph.edges().begin(), graph.edges().end(),
      [from, to](const ContinuationEdge &edge) {
        return edge.from_fragment_id == from && edge.to_fragment_id == to;
      });
  return iterator == graph.edges().end() ? nullptr : &*iterator;
}

[[nodiscard]] bool hard_repetition_constraints(
    const std::vector<PlannedFragment> &sequence,
    const FragmentDescriptor &candidate,
    const ContinuationPlanningRequest &request) noexcept {
  std::vector<std::uint64_t> ids;
  std::vector<std::uint64_t> melodic;
  ids.reserve(sequence.size());
  melodic.reserve(sequence.size());
  std::size_t anchor_count =
      request.anchor_fragment_id != 0U &&
              request.current_context.current_fragment_id ==
                  request.anchor_fragment_id
          ? 1U
          : 0U;
  for (const auto &planned : sequence) {
    ids.push_back(planned.fragment.fragment_id);
    melodic.push_back(planned.fragment.melodic_fingerprint);
    if (planned.fragment.fragment_id == request.anchor_fragment_id) {
      ++anchor_count;
    }
  }
  if (recent_contains(ids, candidate.fragment_id,
                      request.fragment_reuse_window)) {
    return false;
  }
  if (contains(ids, candidate.fragment_id) && candidate.variation_mask == 0U) {
    return false;
  }
  if (candidate.fragment_id == request.anchor_fragment_id && anchor_count >= 2U) {
    return false;
  }
  if (recent_contains(melodic, candidate.melodic_fingerprint,
                      request.fingerprint_window) ||
      (candidate.melodic_fingerprint != 0U &&
       contains(request.recent_melodic_fingerprints,
                candidate.melodic_fingerprint))) {
    return false;
  }
  if (!sequence.empty()) {
    const auto &previous = sequence.back().fragment;
    const auto same_melody = candidate.melodic_fingerprint != 0U &&
                             candidate.melodic_fingerprint ==
                                 previous.melodic_fingerprint;
    const auto same_harmony = candidate.harmonic_fingerprint != 0U &&
                              candidate.harmonic_fingerprint ==
                                  previous.harmonic_fingerprint;
    const auto same_groove = candidate.groove_fingerprint != 0U &&
                             candidate.groove_fingerprint ==
                                 previous.groove_fingerprint;
    if (same_melody && same_harmony && same_groove) {
      return false;
    }

    const auto identity = arrangement_identity(candidate);
    std::uint32_t identical_bars = 0U;
    for (auto iterator = sequence.rbegin(); iterator != sequence.rend();
         ++iterator) {
      if (arrangement_identity(iterator->fragment) != identity) {
        break;
      }
      identical_bars += iterator->bars_used;
    }
    if (identical_bars + candidate.bar_count > 4U) {
      return false;
    }

    if (candidate.audible_layer_mask != 0U) {
      std::uint32_t unchanged_layer_bars = 0U;
      for (auto iterator = sequence.rbegin(); iterator != sequence.rend();
           ++iterator) {
        if (iterator->fragment.audible_layer_mask !=
            candidate.audible_layer_mask) {
          break;
        }
        unchanged_layer_bars += iterator->bars_used;
      }
      if (unchanged_layer_bars + candidate.bar_count > 4U) {
        return false;
      }
    }
  }
  return true;
}

} // namespace

bool FragmentDescriptor::valid() const noexcept {
  return fragment_id != 0U && end_sample > start_sample && bar_count > 0U &&
         bar_count <= 64U && valid_stem_role(stem_role) &&
         valid_source(source) &&
         valid_chunk_kind(chunk_kind) && std::isfinite(beat_phase) &&
         std::isfinite(energy) && std::isfinite(density) &&
         std::isfinite(loopability_score) && std::isfinite(vocal_activity) &&
         std::isfinite(generation_confidence) &&
         std::isfinite(generation_latency_seconds) && finite_vector(bpm_curve) &&
         finite_vector(chroma_embedding) && finite_vector(timbre_embedding) &&
         finite_vector(instrument_embedding) && finite_vector(groove_embedding) &&
         finite_vector(transient_profile) && finite_vector(stereo_profile) &&
         finite_vector(boundary_in_descriptor) &&
         finite_vector(boundary_out_descriptor);
}

std::uint64_t FragmentDescriptor::sample_count() const noexcept {
  return end_sample > start_sample ? end_sample - start_sample : 0U;
}

float ContinuationEdge::score() const noexcept {
  if (!valid_edge_kind(kind)) {
    return 0.0F;
  }
  const float values[] = {
      sample_boundary_continuity, phase_continuity, beat_alignment,
      chord_compatibility,       key_compatibility, timbre_compatibility,
      groove_compatibility,      energy_trajectory, stem_role_fit,
      masking_fit,               latency_fit,       cache_fit,
  };
  auto total = 0.0F;
  for (const auto value : values) {
    total += clamp_unit(value);
  }
  return total / static_cast<float>(sizeof(values) / sizeof(values[0]));
}

bool ContinuationGraph::add_fragment(FragmentDescriptor fragment) {
  if (!fragment.valid() || find_fragment(fragment.fragment_id) != nullptr) {
    return false;
  }
  fragments_.push_back(std::move(fragment));
  return true;
}

bool ContinuationGraph::add_edge(ContinuationEdge edge) {
  if (edge.from_fragment_id == edge.to_fragment_id ||
      find_fragment(edge.from_fragment_id) == nullptr ||
      find_fragment(edge.to_fragment_id) == nullptr ||
      !valid_edge_kind(edge.kind) || !valid_edge_values(edge)) {
    return false;
  }
  const auto duplicate = std::any_of(
      edges_.begin(), edges_.end(), [&edge](const ContinuationEdge &existing) {
        return existing.from_fragment_id == edge.from_fragment_id &&
               existing.to_fragment_id == edge.to_fragment_id &&
               existing.kind == edge.kind;
      });
  if (duplicate) {
    return false;
  }
  edges_.push_back(std::move(edge));
  return true;
}

const FragmentDescriptor *
ContinuationGraph::find_fragment(std::uint64_t fragment_id) const noexcept {
  const auto iterator =
      std::find_if(fragments_.begin(), fragments_.end(),
                   [fragment_id](const FragmentDescriptor &fragment) {
                     return fragment.fragment_id == fragment_id;
                   });
  return iterator == fragments_.end() ? nullptr : &*iterator;
}

std::vector<ContinuationEdge>
ContinuationGraph::outgoing(std::uint64_t fragment_id) const {
  std::vector<ContinuationEdge> result;
  for (const auto &edge : edges_) {
    if (edge.from_fragment_id == fragment_id) {
      result.push_back(edge);
    }
  }
  std::sort(result.begin(), result.end(), [](const auto &left, const auto &right) {
    const auto left_score = left.score();
    const auto right_score = right.score();
    if (std::abs(left_score - right_score) > 1.0e-6F) {
      return left_score > right_score;
    }
    return left.to_fragment_id < right.to_fragment_id;
  });
  return result;
}

std::vector<FragmentDescriptor> ContinuationReservoir::get_candidates(
    const ContinuationContext &current_context, std::uint32_t desired_bars,
    const std::vector<std::uint64_t> &excluded_fragment_ids,
    const std::vector<std::uint64_t> &recent_melodic_fingerprints,
    const ContinuationContext &target_track_context,
    const DeviceBudget &device_budget) const {
  if (device_budget.max_candidates == 0U ||
      device_budget.thermal_headroom < 0.0F ||
      !std::isfinite(device_budget.thermal_headroom)) {
    return {};
  }

  const auto outgoing_edges = graph_.outgoing(current_context.current_fragment_id);
  const auto graph_is_constrained = !outgoing_edges.empty();
  std::unordered_map<std::uint64_t, float> graph_scores;
  for (const auto &edge : outgoing_edges) {
    graph_scores[edge.to_fragment_id] = edge.score();
  }

  struct Ranked final {
    FragmentDescriptor fragment;
    float score{0.0F};
  };
  std::vector<Ranked> ranked;
  for (const auto &fragment : graph_.fragments()) {
    if (!fragment.valid() ||
        fragment.fragment_id == current_context.current_fragment_id ||
        contains(excluded_fragment_ids, fragment.fragment_id) ||
        (fragment.melodic_fingerprint != 0U &&
         contains(recent_melodic_fingerprints,
                  fragment.melodic_fingerprint))) {
      continue;
    }
    const auto edge = graph_scores.find(fragment.fragment_id);
    if (graph_is_constrained && edge == graph_scores.end()) {
      continue;
    }
    if (current_context.stem_role != StemRole::Other &&
        fragment.stem_role != current_context.stem_role) {
      continue;
    }
    if (fragment.source == SourceKind::Generated &&
        (!device_budget.allow_generated ||
         device_budget.thermal_headroom < 0.15F)) {
      continue;
    }
    if (fragment.source == SourceKind::Generated &&
        fragment.generation_confidence > 0.0F &&
        !device_budget.allow_neural) {
      continue;
    }
    ranked.push_back({fragment, descriptor_score(
                                    fragment, current_context,
                                    target_track_context,
                                    std::max<std::uint32_t>(1U, desired_bars),
                                    edge == graph_scores.end() ? 0.5F
                                                               : edge->second)});
  }

  std::sort(ranked.begin(), ranked.end(), [](const auto &left, const auto &right) {
    if (std::abs(left.score - right.score) > 1.0e-6F) {
      return left.score > right.score;
    }
    return left.fragment.fragment_id < right.fragment.fragment_id;
  });
  if (ranked.size() > device_budget.max_candidates) {
    ranked.resize(device_budget.max_candidates);
  }
  std::vector<FragmentDescriptor> result;
  result.reserve(ranked.size());
  for (auto &item : ranked) {
    result.push_back(std::move(item.fragment));
  }
  return result;
}

ContinuationPlan NonRepeatingContinuationPlanner::plan(
    const ContinuationReservoir &reservoir,
    const ContinuationPlanningRequest &request) const {
  ContinuationPlan result;
  const auto target_bars =
      std::max<std::uint32_t>(16U, std::min<std::uint32_t>(32U,
                                                           request.target_bars));
  const auto beam_width = std::max<std::size_t>(1U, request.beam_width);
  const auto maximum_expansions = std::min(
      request.max_expansions, request.device_budget.max_expansions);
  if (maximum_expansions == 0U) {
    return result;
  }

  struct BeamState final {
    std::vector<PlannedFragment> fragments;
    ContinuationContext context{};
    std::uint32_t bars{0};
    double score{0.0};
  };
  std::vector<BeamState> beam{{{}, request.current_context, 0U, 0.0}};
  std::optional<BeamState> best_complete;
  std::optional<BeamState> best_partial;
  std::size_t expansions = 0U;

  while (!beam.empty() && expansions < maximum_expansions) {
    std::vector<BeamState> expanded;
    for (const auto &parent : beam) {
      auto excluded = request.excluded_fragment_ids;
      const auto exclusion_start =
          parent.fragments.size() > request.fragment_reuse_window
              ? parent.fragments.size() - request.fragment_reuse_window
              : 0U;
      for (std::size_t index = exclusion_start;
           index < parent.fragments.size(); ++index) {
        excluded.push_back(parent.fragments[index].fragment.fragment_id);
      }
      auto melodic = request.recent_melodic_fingerprints;
      const auto fingerprint_start =
          parent.fragments.size() > request.fingerprint_window
              ? parent.fragments.size() - request.fingerprint_window
              : 0U;
      for (std::size_t index = fingerprint_start;
           index < parent.fragments.size(); ++index) {
        const auto value = parent.fragments[index].fragment.melodic_fingerprint;
        if (value != 0U) {
          melodic.push_back(value);
        }
      }

      auto budget = request.device_budget;
      budget.max_candidates = std::min<std::size_t>(
          budget.max_candidates, maximum_expansions - expansions);
      const auto candidates = reservoir.get_candidates(
          parent.context, target_bars - parent.bars, excluded, melodic,
          request.target_context, budget);
      for (const auto &candidate : candidates) {
        if (expansions >= maximum_expansions) {
          break;
        }
        ++expansions;
        if (!hard_repetition_constraints(parent.fragments, candidate,
                                         request)) {
          continue;
        }
        const auto *edge = find_edge(reservoir.graph(),
                                     parent.context.current_fragment_id,
                                     candidate.fragment_id);
        const auto edge_score = edge == nullptr ? 0.5 : edge->score();
        auto child = parent;
        const auto remaining = target_bars - child.bars;
        const auto used = std::min(candidate.bar_count, remaining);
        child.fragments.push_back(
            {candidate, child.bars, used, edge_score});
        child.bars += used;
        child.score += edge_score + 0.15 +
                       (candidate.cache_available ? 0.08 : 0.0) -
                       0.02 * candidate.generation_latency_seconds;
        child.context.current_fragment_id = candidate.fragment_id;
        child.context.track_id = candidate.track_id;
        child.context.stem_role = candidate.stem_role;
        child.context.beat_phase = candidate.beat_phase;
        child.context.key = candidate.key;
        child.context.chord = candidate.chord_end;
        child.context.energy = candidate.energy;
        child.context.audible_layer_mask = candidate.audible_layer_mask;

        if (!best_partial || child.bars > best_partial->bars ||
            (child.bars == best_partial->bars &&
             child.score > best_partial->score)) {
          best_partial = child;
        }
        if (child.bars >= target_bars) {
          const auto repetition =
              RepetitionQualityEvaluator{}.evaluate(child.fragments);
          if (repetition.accepted &&
              (!best_complete || child.score > best_complete->score)) {
            best_complete = child;
          }
        } else {
          expanded.push_back(std::move(child));
        }
      }
    }

    std::sort(expanded.begin(), expanded.end(),
              [](const auto &left, const auto &right) {
                if (left.bars != right.bars) {
                  return left.bars > right.bars;
                }
                if (std::abs(left.score - right.score) > 1.0e-12) {
                  return left.score > right.score;
                }
                const auto left_id = left.fragments.empty()
                                         ? 0U
                                         : left.fragments.back()
                                               .fragment.fragment_id;
                const auto right_id = right.fragments.empty()
                                          ? 0U
                                          : right.fragments.back()
                                                .fragment.fragment_id;
                return left_id < right_id;
              });
    if (expanded.size() > beam_width) {
      expanded.resize(beam_width);
    }
    beam = std::move(expanded);
  }

  const auto selected = best_complete ? best_complete : best_partial;
  result.expansions = expansions;
  if (selected) {
    result.fragments = selected->fragments;
    result.total_bars = selected->bars;
    result.score = selected->score;
    const auto repetition = RepetitionQualityEvaluator{}.evaluate(result.fragments);
    result.score += 0.25 * repetition.metrics.novelty_per_bar -
                    repetition.metrics.exact_fragment_repeat_rate -
                    repetition.metrics.melodic_repeat_rate -
                    repetition.metrics.full_arrangement_repeat_rate;
    result.complete = selected->bars >= target_bars && repetition.accepted;
  }
  return result;
}

RepetitionEvaluation RepetitionQualityEvaluator::evaluate(
    const std::vector<PlannedFragment> &fragments,
    const RepetitionLimits &limits) const noexcept {
  RepetitionEvaluation result;
  if (fragments.empty()) {
    return result;
  }

  std::unordered_set<std::uint64_t> fragment_ids;
  std::unordered_set<std::uint64_t> melodic;
  std::unordered_set<std::uint64_t> harmonic;
  std::unordered_set<std::uint64_t> rhythmic;
  std::unordered_set<std::uint64_t> arrangements;
  std::size_t exact_repeats = 0U;
  std::size_t melodic_repeats = 0U;
  std::size_t harmonic_repeats = 0U;
  std::size_t rhythmic_repeats = 0U;
  std::size_t arrangement_repeats = 0U;
  std::uint32_t total_bars = 0U;
  std::uint32_t novel_bars = 0U;
  std::uint32_t current_identical_bars = 0U;
  std::uint64_t previous_arrangement = 0U;
  bool has_previous_arrangement = false;

  for (const auto &planned : fragments) {
    const auto &fragment = planned.fragment;
    const auto bars = std::max<std::uint32_t>(1U, planned.bars_used);
    total_bars += bars;
    if (!fragment_ids.insert(fragment.fragment_id).second) {
      ++exact_repeats;
    }
    if (fragment.melodic_fingerprint != 0U &&
        !melodic.insert(fragment.melodic_fingerprint).second) {
      ++melodic_repeats;
    }
    if (fragment.harmonic_fingerprint != 0U &&
        !harmonic.insert(fragment.harmonic_fingerprint).second) {
      ++harmonic_repeats;
    }
    if (fragment.rhythmic_fingerprint != 0U &&
        !rhythmic.insert(fragment.rhythmic_fingerprint).second) {
      ++rhythmic_repeats;
    }
    const auto arrangement = arrangement_identity(fragment);
    if (!arrangements.insert(arrangement).second) {
      ++arrangement_repeats;
    } else {
      novel_bars += bars;
    }
    if (has_previous_arrangement && arrangement == previous_arrangement) {
      current_identical_bars += bars;
    } else {
      current_identical_bars = bars;
    }
    result.metrics.longest_identical_run_bars = std::max(
        result.metrics.longest_identical_run_bars, current_identical_bars);
    previous_arrangement = arrangement;
    has_previous_arrangement = true;
  }

  const auto count = static_cast<float>(fragments.size());
  result.metrics.exact_fragment_repeat_rate = exact_repeats / count;
  result.metrics.melodic_repeat_rate = melodic_repeats / count;
  result.metrics.harmonic_repeat_rate = harmonic_repeats / count;
  result.metrics.rhythmic_repeat_rate = rhythmic_repeats / count;
  result.metrics.full_arrangement_repeat_rate = arrangement_repeats / count;
  result.metrics.novelty_per_bar =
      total_bars == 0U ? 1.0F
                       : static_cast<float>(novel_bars) / total_bars;
  result.accepted =
      result.metrics.exact_fragment_repeat_rate <=
          limits.max_exact_fragment_repeat_rate &&
      result.metrics.melodic_repeat_rate <= limits.max_melodic_repeat_rate &&
      result.metrics.full_arrangement_repeat_rate <=
          limits.max_full_arrangement_repeat_rate &&
      result.metrics.longest_identical_run_bars <=
          limits.max_identical_run_bars &&
      result.metrics.novelty_per_bar >= limits.min_novelty_per_bar;
  return result;
}

namespace {

class BinaryWriter final {
public:
  void u8(std::uint8_t value) { bytes_.push_back(value); }

  void u32(std::uint32_t value) {
    for (unsigned shift = 0U; shift < 32U; shift += 8U) {
      u8(static_cast<std::uint8_t>((value >> shift) & 0xFFU));
    }
  }

  void u64(std::uint64_t value) {
    for (unsigned shift = 0U; shift < 64U; shift += 8U) {
      u8(static_cast<std::uint8_t>((value >> shift) & 0xFFU));
    }
  }

  void i32(std::int32_t value) { u32(static_cast<std::uint32_t>(value)); }
  void i64(std::int64_t value) { u64(static_cast<std::uint64_t>(value)); }

  void f32(float value) {
    std::uint32_t bits = 0U;
    static_assert(sizeof(bits) == sizeof(value), "float must be 32-bit");
    std::memcpy(&bits, &value, sizeof(bits));
    u32(bits);
  }

  bool count(std::size_t value) {
    if (value > kMaximumSerializedItems) {
      valid_ = false;
      return false;
    }
    u32(static_cast<std::uint32_t>(value));
    return true;
  }

  void floats(const std::vector<float> &values) {
    if (!count(values.size())) {
      return;
    }
    for (const auto value : values) {
      f32(value);
    }
  }

  void ids(const std::vector<std::uint64_t> &values) {
    if (!count(values.size())) {
      return;
    }
    for (const auto value : values) {
      u64(value);
    }
  }

  void opaque(const std::vector<std::uint8_t> &values) {
    if (!count(values.size())) {
      return;
    }
    bytes_.insert(bytes_.end(), values.begin(), values.end());
  }

  [[nodiscard]] bool valid() const noexcept { return valid_; }
  [[nodiscard]] std::vector<std::uint8_t> take() { return std::move(bytes_); }

private:
  std::vector<std::uint8_t> bytes_;
  bool valid_{true};
};

class BinaryReader final {
public:
  BinaryReader(const std::uint8_t *bytes, std::size_t size)
      : bytes_(bytes), size_(size) {}

  bool u8(std::uint8_t &value) noexcept {
    if (!take(1U)) {
      return false;
    }
    value = bytes_[position_++];
    return true;
  }

  bool u32(std::uint32_t &value) noexcept {
    if (!take(4U)) {
      return false;
    }
    value = 0U;
    for (unsigned shift = 0U; shift < 32U; shift += 8U) {
      value |= static_cast<std::uint32_t>(bytes_[position_++]) << shift;
    }
    return true;
  }

  bool u64(std::uint64_t &value) noexcept {
    if (!take(8U)) {
      return false;
    }
    value = 0U;
    for (unsigned shift = 0U; shift < 64U; shift += 8U) {
      value |= static_cast<std::uint64_t>(bytes_[position_++]) << shift;
    }
    return true;
  }

  bool i32(std::int32_t &value) noexcept {
    std::uint32_t bits = 0U;
    if (!u32(bits)) {
      return false;
    }
    value = static_cast<std::int32_t>(bits);
    return true;
  }

  bool i64(std::int64_t &value) noexcept {
    std::uint64_t bits = 0U;
    if (!u64(bits)) {
      return false;
    }
    value = static_cast<std::int64_t>(bits);
    return true;
  }

  bool f32(float &value) noexcept {
    std::uint32_t bits = 0U;
    if (!u32(bits)) {
      return false;
    }
    std::memcpy(&value, &bits, sizeof(value));
    return std::isfinite(value);
  }

  bool count(std::size_t &value) noexcept {
    std::uint32_t stored = 0U;
    if (!u32(stored) || stored > kMaximumSerializedItems) {
      return false;
    }
    value = stored;
    return true;
  }

  bool floats(std::vector<float> &values) {
    std::size_t count_value = 0U;
    if (!count(count_value) || count_value > (size_ - position_) / 4U) {
      return false;
    }
    values.resize(count_value);
    for (auto &value : values) {
      if (!f32(value)) {
        return false;
      }
    }
    return true;
  }

  bool ids(std::vector<std::uint64_t> &values) {
    std::size_t count_value = 0U;
    if (!count(count_value) || count_value > (size_ - position_) / 8U) {
      return false;
    }
    values.resize(count_value);
    for (auto &value : values) {
      if (!u64(value)) {
        return false;
      }
    }
    return true;
  }

  bool opaque(std::vector<std::uint8_t> &values) {
    std::size_t count_value = 0U;
    if (!count(count_value) || !take(count_value)) {
      return false;
    }
    values.assign(bytes_ + position_, bytes_ + position_ + count_value);
    position_ += count_value;
    return true;
  }

  [[nodiscard]] bool finished() const noexcept { return position_ == size_; }
  [[nodiscard]] std::size_t remaining() const noexcept {
    return position_ <= size_ ? size_ - position_ : 0U;
  }

private:
  bool take(std::size_t count_value) const noexcept {
    return bytes_ != nullptr && count_value <= size_ - position_;
  }

  const std::uint8_t *bytes_{nullptr};
  std::size_t size_{0};
  std::size_t position_{0};
};

void write_fragment(BinaryWriter &writer,
                    const FragmentDescriptor &fragment) {
  writer.u64(fragment.fragment_id);
  writer.u64(fragment.track_id);
  writer.u8(static_cast<std::uint8_t>(fragment.stem_role));
  writer.u8(static_cast<std::uint8_t>(fragment.source));
  writer.u8(static_cast<std::uint8_t>(fragment.chunk_kind));
  writer.u64(fragment.start_sample);
  writer.u64(fragment.end_sample);
  writer.u32(fragment.bar_count);
  writer.f32(fragment.beat_phase);
  writer.i64(fragment.downbeat_offset);
  writer.floats(fragment.bpm_curve);
  writer.i32(fragment.key);
  writer.i32(fragment.chord_start);
  writer.i32(fragment.chord_end);
  writer.floats(fragment.chroma_embedding);
  writer.floats(fragment.timbre_embedding);
  writer.floats(fragment.instrument_embedding);
  writer.u64(fragment.melodic_fingerprint);
  writer.u64(fragment.harmonic_fingerprint);
  writer.u64(fragment.rhythmic_fingerprint);
  writer.u64(fragment.groove_fingerprint);
  writer.u64(fragment.arrangement_fingerprint);
  writer.floats(fragment.groove_embedding);
  writer.f32(fragment.energy);
  writer.f32(fragment.density);
  writer.floats(fragment.transient_profile);
  writer.floats(fragment.stereo_profile);
  writer.f32(fragment.loopability_score);
  writer.floats(fragment.boundary_in_descriptor);
  writer.floats(fragment.boundary_out_descriptor);
  writer.f32(fragment.vocal_activity);
  writer.f32(fragment.generation_confidence);
  writer.f32(fragment.generation_latency_seconds);
  writer.u8(fragment.cache_available ? 1U : 0U);
  writer.u64(fragment.audible_layer_mask);
  writer.u64(fragment.source_identity);
  writer.u32(fragment.variation_mask);
}

bool read_fragment(BinaryReader &reader, FragmentDescriptor &fragment) {
  std::uint8_t role = 0U;
  std::uint8_t source = 0U;
  std::uint8_t kind = 0U;
  std::uint8_t cached = 0U;
  if (!reader.u64(fragment.fragment_id) || !reader.u64(fragment.track_id) ||
      !reader.u8(role) || !reader.u8(source) || !reader.u8(kind) ||
      !reader.u64(fragment.start_sample) ||
      !reader.u64(fragment.end_sample) || !reader.u32(fragment.bar_count) ||
      !reader.f32(fragment.beat_phase) ||
      !reader.i64(fragment.downbeat_offset) ||
      !reader.floats(fragment.bpm_curve) || !reader.i32(fragment.key) ||
      !reader.i32(fragment.chord_start) || !reader.i32(fragment.chord_end) ||
      !reader.floats(fragment.chroma_embedding) ||
      !reader.floats(fragment.timbre_embedding) ||
      !reader.floats(fragment.instrument_embedding) ||
      !reader.u64(fragment.melodic_fingerprint) ||
      !reader.u64(fragment.harmonic_fingerprint) ||
      !reader.u64(fragment.rhythmic_fingerprint) ||
      !reader.u64(fragment.groove_fingerprint) ||
      !reader.u64(fragment.arrangement_fingerprint) ||
      !reader.floats(fragment.groove_embedding) ||
      !reader.f32(fragment.energy) || !reader.f32(fragment.density) ||
      !reader.floats(fragment.transient_profile) ||
      !reader.floats(fragment.stereo_profile) ||
      !reader.f32(fragment.loopability_score) ||
      !reader.floats(fragment.boundary_in_descriptor) ||
      !reader.floats(fragment.boundary_out_descriptor) ||
      !reader.f32(fragment.vocal_activity) ||
      !reader.f32(fragment.generation_confidence) ||
      !reader.f32(fragment.generation_latency_seconds) || !reader.u8(cached) ||
      !reader.u64(fragment.audible_layer_mask) ||
      !reader.u64(fragment.source_identity) ||
      !reader.u32(fragment.variation_mask)) {
    return false;
  }
  fragment.stem_role = static_cast<StemRole>(role);
  fragment.source = static_cast<SourceKind>(source);
  fragment.chunk_kind = static_cast<ChunkKind>(kind);
  fragment.cache_available = cached != 0U;
  return cached <= 1U && fragment.valid();
}

void write_edge(BinaryWriter &writer, const ContinuationEdge &edge) {
  writer.u64(edge.from_fragment_id);
  writer.u64(edge.to_fragment_id);
  writer.u8(static_cast<std::uint8_t>(edge.kind));
  writer.f32(edge.sample_boundary_continuity);
  writer.f32(edge.phase_continuity);
  writer.f32(edge.beat_alignment);
  writer.f32(edge.chord_compatibility);
  writer.f32(edge.key_compatibility);
  writer.f32(edge.timbre_compatibility);
  writer.f32(edge.groove_compatibility);
  writer.f32(edge.energy_trajectory);
  writer.f32(edge.stem_role_fit);
  writer.f32(edge.masking_fit);
  writer.f32(edge.latency_fit);
  writer.f32(edge.cache_fit);
}

bool read_edge(BinaryReader &reader, ContinuationEdge &edge) {
  std::uint8_t kind = 0U;
  if (!reader.u64(edge.from_fragment_id) ||
      !reader.u64(edge.to_fragment_id) || !reader.u8(kind) ||
      !reader.f32(edge.sample_boundary_continuity) ||
      !reader.f32(edge.phase_continuity) ||
      !reader.f32(edge.beat_alignment) ||
      !reader.f32(edge.chord_compatibility) ||
      !reader.f32(edge.key_compatibility) ||
      !reader.f32(edge.timbre_compatibility) ||
      !reader.f32(edge.groove_compatibility) ||
      !reader.f32(edge.energy_trajectory) ||
      !reader.f32(edge.stem_role_fit) || !reader.f32(edge.masking_fit) ||
      !reader.f32(edge.latency_fit) || !reader.f32(edge.cache_fit)) {
    return false;
  }
  edge.kind = static_cast<GraphEdgeKind>(kind);
  return valid_edge_kind(edge.kind);
}

void write_job(BinaryWriter &writer, const ChunkJobProgress &job) {
  writer.u64(job.chunk_id);
  writer.u8(static_cast<std::uint8_t>(job.chunk_kind));
  writer.u64(job.start_sample);
  writer.u64(job.end_sample);
  writer.u64(job.next_sample);
  writer.u8(static_cast<std::uint8_t>(job.state));
  writer.u32(job.attempt_count);
  writer.u64(job.checksum);
}

bool read_job(BinaryReader &reader, ChunkJobProgress &job) {
  std::uint8_t kind = 0U;
  std::uint8_t state = 0U;
  if (!reader.u64(job.chunk_id) || !reader.u8(kind) ||
      !reader.u64(job.start_sample) || !reader.u64(job.end_sample) ||
      !reader.u64(job.next_sample) || !reader.u8(state) ||
      !reader.u32(job.attempt_count) || !reader.u64(job.checksum)) {
    return false;
  }
  job.chunk_kind = static_cast<ChunkKind>(kind);
  job.state = static_cast<PreprocessChunkState>(state);
  return job.valid();
}

template <typename T, typename Reader>
bool read_items(BinaryReader &reader, std::vector<T> &items,
                Reader read_item, std::size_t minimum_item_bytes) {
  std::size_t count_value = 0U;
  if (!reader.count(count_value) || minimum_item_bytes == 0U ||
      count_value > reader.remaining() / minimum_item_bytes) {
    return false;
  }
  items.resize(count_value);
  for (auto &item : items) {
    if (!read_item(reader, item)) {
      return false;
    }
  }
  return true;
}

} // namespace

bool ChunkJobProgress::valid() const noexcept {
  const auto valid_state = state == PreprocessChunkState::Pending ||
                           state == PreprocessChunkState::Processing ||
                           state == PreprocessChunkState::Complete ||
                           state == PreprocessChunkState::Failed;
  return chunk_id != 0U && valid_chunk_kind(chunk_kind) && valid_state &&
         end_sample > start_sample && next_sample >= start_sample &&
         next_sample <= end_sample &&
         (state != PreprocessChunkState::Complete || next_sample == end_sample);
}

bool PreprocessedTrack::valid() const noexcept {
  const auto count_fits = [](const auto &items) noexcept {
    return items.size() <= kMaximumSerializedItems;
  };
  if (schema_version != kPreprocessedTrackSchemaVersion || track_id == 0U ||
      analyzer_revision == 0U || !finite_vector(track_embedding) ||
      !finite_vector(stem_embeddings) ||
      !finite_vector(cached_encoder_output) ||
      !finite_vector(cached_conditioning_state) ||
      !count_fits(continuation_graph.fragments()) ||
      !count_fits(continuation_graph.edges()) || !count_fits(chunk_jobs) ||
      !count_fits(entry_port_fragment_ids) ||
      !count_fits(exit_port_fragment_ids) ||
      !count_fits(instrument_similarity_fragment_ids) ||
      !count_fits(loop_candidate_fragment_ids) ||
      !count_fits(variation_candidate_fragment_ids) ||
      !count_fits(fill_candidate_fragment_ids) ||
      !count_fits(track_embedding) || !count_fits(stem_embeddings) ||
      !count_fits(cached_encoder_output) ||
      !count_fits(cached_conditioning_state) ||
      !count_fits(quantized_stem_representation) ||
      !count_fits(audio_codec_tokens)) {
    return false;
  }
  for (const auto &fragment : continuation_graph.fragments()) {
    if (!fragment.valid() ||
        (fragment.track_id != 0U && fragment.track_id != track_id)) {
      return false;
    }
  }
  const auto valid_fragment_ids = [this](
                                      const std::vector<std::uint64_t> &ids) {
    return std::all_of(ids.begin(), ids.end(), [this](std::uint64_t id) {
      return continuation_graph.find_fragment(id) != nullptr;
    });
  };
  return std::all_of(chunk_jobs.begin(), chunk_jobs.end(),
                     [](const ChunkJobProgress &job) { return job.valid(); }) &&
         valid_fragment_ids(entry_port_fragment_ids) &&
         valid_fragment_ids(exit_port_fragment_ids) &&
         valid_fragment_ids(instrument_similarity_fragment_ids) &&
         valid_fragment_ids(loop_candidate_fragment_ids) &&
         valid_fragment_ids(variation_candidate_fragment_ids) &&
         valid_fragment_ids(fill_candidate_fragment_ids);
}

bool PreprocessedTrack::preprocessing_complete() const noexcept {
  return valid() &&
         std::all_of(chunk_jobs.begin(), chunk_jobs.end(),
                     [](const ChunkJobProgress &job) {
                       return job.state == PreprocessChunkState::Complete;
                     });
}

std::vector<FragmentDescriptor>
PreprocessedTrack::chunk_bank(ChunkKind kind) const {
  std::vector<FragmentDescriptor> result;
  for (const auto &fragment : continuation_graph.fragments()) {
    if (fragment.chunk_kind == kind) {
      result.push_back(fragment);
    }
  }
  return result;
}

std::vector<std::uint8_t> PreprocessedTrack::serialize() const {
  if (!valid()) {
    return {};
  }
  BinaryWriter writer;
  for (const auto value : kPreprocessedMagic) {
    writer.u8(value);
  }
  writer.u32(schema_version);
  writer.u32(analyzer_revision);
  writer.u64(track_id);
  writer.u64(content_revision);
  writer.u32(model_revision);
  if (!writer.count(continuation_graph.fragments().size())) {
    return {};
  }
  for (const auto &fragment : continuation_graph.fragments()) {
    write_fragment(writer, fragment);
  }
  if (!writer.count(continuation_graph.edges().size())) {
    return {};
  }
  for (const auto &edge : continuation_graph.edges()) {
    write_edge(writer, edge);
  }
  if (!writer.count(chunk_jobs.size())) {
    return {};
  }
  for (const auto &job : chunk_jobs) {
    write_job(writer, job);
  }
  writer.ids(entry_port_fragment_ids);
  writer.ids(exit_port_fragment_ids);
  writer.ids(instrument_similarity_fragment_ids);
  writer.ids(loop_candidate_fragment_ids);
  writer.ids(variation_candidate_fragment_ids);
  writer.ids(fill_candidate_fragment_ids);
  writer.floats(track_embedding);
  writer.floats(stem_embeddings);
  writer.floats(cached_encoder_output);
  writer.floats(cached_conditioning_state);
  writer.opaque(quantized_stem_representation);
  writer.opaque(audio_codec_tokens);
  return writer.valid() ? writer.take() : std::vector<std::uint8_t>{};
}

std::optional<PreprocessedTrack>
PreprocessedTrack::deserialize(const std::uint8_t *bytes,
                               std::size_t size) noexcept {
  try {
    if (bytes == nullptr || size < sizeof(kPreprocessedMagic) + 4U) {
      return std::nullopt;
    }
    BinaryReader reader(bytes, size);
    for (const auto expected : kPreprocessedMagic) {
      std::uint8_t actual = 0U;
      if (!reader.u8(actual) || actual != expected) {
        return std::nullopt;
      }
    }
    PreprocessedTrack result;
    if (!reader.u32(result.schema_version) ||
        result.schema_version != kPreprocessedTrackSchemaVersion ||
        !reader.u32(result.analyzer_revision) || !reader.u64(result.track_id) ||
        !reader.u64(result.content_revision) ||
        !reader.u32(result.model_revision)) {
      return std::nullopt;
    }
    std::vector<FragmentDescriptor> fragments;
    if (!read_items(reader, fragments, read_fragment,
                    kMinimumSerializedFragmentBytes)) {
      return std::nullopt;
    }
    for (auto &fragment : fragments) {
      if (!result.continuation_graph.add_fragment(std::move(fragment))) {
        return std::nullopt;
      }
    }
    std::vector<ContinuationEdge> edges;
    if (!read_items(reader, edges, read_edge,
                    kMinimumSerializedEdgeBytes)) {
      return std::nullopt;
    }
    for (auto &edge : edges) {
      if (!result.continuation_graph.add_edge(std::move(edge))) {
        return std::nullopt;
      }
    }
    if (!read_items(reader, result.chunk_jobs, read_job,
                    kSerializedChunkJobBytes) ||
        !reader.ids(result.entry_port_fragment_ids) ||
        !reader.ids(result.exit_port_fragment_ids) ||
        !reader.ids(result.instrument_similarity_fragment_ids) ||
        !reader.ids(result.loop_candidate_fragment_ids) ||
        !reader.ids(result.variation_candidate_fragment_ids) ||
        !reader.ids(result.fill_candidate_fragment_ids) ||
        !reader.floats(result.track_embedding) ||
        !reader.floats(result.stem_embeddings) ||
        !reader.floats(result.cached_encoder_output) ||
        !reader.floats(result.cached_conditioning_state) ||
        !reader.opaque(result.quantized_stem_representation) ||
        !reader.opaque(result.audio_codec_tokens) || !reader.finished() ||
        !result.valid()) {
      return std::nullopt;
    }
    return result;
  } catch (...) {
    return std::nullopt;
  }
}

ProgressivePcmBuffer::ProgressivePcmBuffer(ProgressiveBufferConfig config)
    : config_(config) {
  const auto bars_fit = config_.frames_per_bar > 0U &&
                        config_.planning_bars <=
                            std::numeric_limits<std::uint64_t>::max() /
                                config_.frames_per_bar;
  if (config_.channels == 0U || config_.channels > 32U || !bars_fit ||
      config_.committed_bars == 0U ||
      config_.guaranteed_bars < config_.committed_bars ||
      config_.target_bars < config_.guaranteed_bars ||
      config_.planning_bars < config_.target_bars ||
      config_.low_watermark_bars == 0U ||
      config_.low_watermark_bars > config_.guaranteed_bars ||
      config_.high_watermark_bars < config_.low_watermark_bars ||
      config_.high_watermark_bars > config_.planning_bars) {
    throw std::invalid_argument("invalid progressive PCM buffer configuration");
  }
}

std::uint64_t
ProgressivePcmBuffer::bars_to_frames(std::uint32_t bars) const noexcept {
  return config_.frames_per_bar * bars;
}

std::uint64_t ProgressivePcmBuffer::rendered_end_frame_locked() const noexcept {
  return buffer_start_frame_ +
         static_cast<std::uint64_t>(interleaved_.size() / config_.channels);
}

std::uint64_t ProgressivePcmBuffer::available_frames_locked() const noexcept {
  const auto total =
      static_cast<std::uint64_t>(interleaved_.size() / config_.channels);
  return read_offset_frames_ >= total ? 0U : total - read_offset_frames_;
}

void ProgressivePcmBuffer::update_watermark_locked() noexcept {
  const auto above =
      available_frames_locked() > bars_to_frames(config_.low_watermark_bars);
  if (!above && previously_above_low_watermark_) {
    ++low_watermark_events_;
  }
  previously_above_low_watermark_ = above;
}

bool ProgressivePcmBuffer::append_pcm(const std::vector<float> &interleaved,
                                      CandidateLevel level,
                                      std::uint64_t candidate_id) {
  if (interleaved.empty() ||
      interleaved.size() % config_.channels != 0U ||
      !finite_vector(interleaved) ||
      (level != CandidateLevel::LEVEL_0 &&
       level != CandidateLevel::LEVEL_1 &&
       level != CandidateLevel::LEVEL_2)) {
    return false;
  }
  const auto frames =
      static_cast<std::uint64_t>(interleaved.size() / config_.channels);
  const std::lock_guard<std::mutex> lock(mutex_);
  const auto available = available_frames_locked();
  const auto planning = bars_to_frames(config_.planning_bars);
  if ((candidate_id != 0U && candidate_id == last_candidate_id_) ||
      frames > planning || available > planning - frames ||
      interleaved_.size() >
          std::numeric_limits<std::size_t>::max() - interleaved.size()) {
    return false;
  }
  interleaved_.insert(interleaved_.end(), interleaved.begin(),
                      interleaved.end());
  active_candidate_level_ = level;
  last_candidate_id_ = candidate_id;
  update_watermark_locked();
  return true;
}

bool ProgressivePcmBuffer::append_deterministic(
    const std::vector<float> &interleaved, CandidateLevel level,
    std::uint64_t candidate_id) {
  if (level == CandidateLevel::LEVEL_2) {
    return false;
  }
  return append_pcm(interleaved, level, candidate_id);
}

bool ProgressivePcmBuffer::replace_uncommitted_with_neural(
    std::uint64_t timeline_start_frame,
    const std::vector<float> &interleaved, std::uint64_t candidate_id) {
  if (interleaved.empty() ||
      interleaved.size() % config_.channels != 0U ||
      !finite_vector(interleaved)) {
    return false;
  }
  const auto frames =
      static_cast<std::uint64_t>(interleaved.size() / config_.channels);
  if (timeline_start_frame % config_.frames_per_bar != 0U ||
      frames % config_.frames_per_bar != 0U) {
    return false;
  }

  const std::lock_guard<std::mutex> lock(mutex_);
  const auto playback = buffer_start_frame_ + read_offset_frames_;
  const auto rendered_end = rendered_end_frame_locked();
  const auto committed_end =
      std::min(rendered_end,
               playback + bars_to_frames(config_.committed_bars));
  if (timeline_start_frame < committed_end ||
      timeline_start_frame < buffer_start_frame_ ||
      timeline_start_frame > rendered_end ||
      frames > rendered_end - timeline_start_frame) {
    return false;
  }
  const auto sample_offset = static_cast<std::size_t>(
      (timeline_start_frame - buffer_start_frame_) * config_.channels);
  const auto overlap_frames = std::min<std::uint64_t>(
      frames / 2U, std::max<std::uint64_t>(2U, config_.frames_per_bar));
  const auto analysis_frames = std::max<std::uint64_t>(1U, overlap_frames);
  double reference_energy = 0.0;
  double neural_energy = 0.0;
  double correlation = 0.0;
  for (std::uint64_t frame = 0U; frame < analysis_frames; ++frame) {
    for (std::uint32_t channel = 0U; channel < config_.channels; ++channel) {
      const auto local = static_cast<std::size_t>(frame * config_.channels +
                                                  channel);
      const auto reference = interleaved_[sample_offset + local];
      const auto neural = interleaved[local];
      reference_energy += static_cast<double>(reference) * reference;
      neural_energy += static_cast<double>(neural) * neural;
      correlation += static_cast<double>(reference) * neural;
    }
  }
  auto gain = 1.0;
  if (reference_energy > 1.0e-20 && neural_energy > 1.0e-20) {
    gain = std::max(0.5, std::min(std::sqrt(reference_energy / neural_energy),
                                  2.0));
  }
  const auto normalized_correlation =
      reference_energy > 1.0e-20 && neural_energy > 1.0e-20
          ? correlation / std::sqrt(reference_energy * neural_energy)
          : 0.0;
  const auto polarity = normalized_correlation < -0.25 ? -1.0 : 1.0;
  const auto smoothstep = [](double value) noexcept {
    const auto bounded = std::max(0.0, std::min(value, 1.0));
    return bounded * bounded * (3.0 - 2.0 * bounded);
  };
  for (std::uint64_t frame = 0U; frame < frames; ++frame) {
    auto neural_weight = 1.0;
    if (overlap_frames > 1U) {
      const auto denominator = static_cast<double>(overlap_frames - 1U);
      neural_weight = std::min(
          smoothstep(static_cast<double>(frame) / denominator),
          smoothstep(static_cast<double>(frames - 1U - frame) / denominator));
    }
    for (std::uint32_t channel = 0U; channel < config_.channels; ++channel) {
      const auto local = static_cast<std::size_t>(frame * config_.channels +
                                                  channel);
      const auto destination_index = sample_offset + local;
      const auto reference = static_cast<double>(interleaved_[destination_index]);
      const auto aligned_neural =
          static_cast<double>(interleaved[local]) * gain * polarity;
      interleaved_[destination_index] = static_cast<float>(
          reference + (aligned_neural - reference) * neural_weight);
    }
  }
  ++neural_upgrades_applied_;
  active_candidate_level_ = CandidateLevel::LEVEL_2;
  last_candidate_id_ = candidate_id;
  return true;
}

std::uint64_t ProgressivePcmBuffer::read(float *destination,
                                         std::uint64_t frames) noexcept {
  if (destination == nullptr || frames == 0U) {
    return 0U;
  }
  const std::lock_guard<std::mutex> lock(mutex_);
  const auto amount = std::min(frames, available_frames_locked());
  if (amount == 0U ||
      amount > std::numeric_limits<std::size_t>::max() / config_.channels) {
    return 0U;
  }
  const auto source_offset = static_cast<std::size_t>(
      read_offset_frames_ * config_.channels);
  const auto sample_count =
      static_cast<std::size_t>(amount * config_.channels);
  std::copy_n(interleaved_.data() + source_offset, sample_count, destination);
  read_offset_frames_ += amount;
  update_watermark_locked();
  return amount;
}

void ProgressivePcmBuffer::discard_consumed() {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (read_offset_frames_ == 0U) {
    return;
  }
  const auto samples = static_cast<std::size_t>(
      read_offset_frames_ * config_.channels);
  interleaved_.erase(
      interleaved_.begin(),
      interleaved_.begin() + static_cast<std::ptrdiff_t>(samples));
  buffer_start_frame_ += read_offset_frames_;
  read_offset_frames_ = 0U;
}

void ProgressivePcmBuffer::reset(std::uint64_t playback_frame) {
  const std::lock_guard<std::mutex> lock(mutex_);
  interleaved_.clear();
  buffer_start_frame_ = playback_frame;
  read_offset_frames_ = 0U;
  low_watermark_events_ = 0U;
  neural_upgrades_applied_ = 0U;
  last_candidate_id_ = 0U;
  active_candidate_level_ = CandidateLevel::LEVEL_0;
  previously_above_low_watermark_ = false;
}

bool ProgressivePcmBuffer::needs_deterministic_recovery() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return available_frames_locked() <=
         bars_to_frames(config_.low_watermark_bars);
}

bool ProgressivePcmBuffer::activation_ready() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return available_frames_locked() >=
         bars_to_frames(config_.guaranteed_bars);
}

std::uint64_t ProgressivePcmBuffer::playback_frame() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return buffer_start_frame_ + read_offset_frames_;
}

std::uint64_t ProgressivePcmBuffer::rendered_end_frame() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return rendered_end_frame_locked();
}

std::uint64_t ProgressivePcmBuffer::committed_end_frame() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  const auto playback = buffer_start_frame_ + read_offset_frames_;
  return std::min(rendered_end_frame_locked(),
                  playback + bars_to_frames(config_.committed_bars));
}

std::uint64_t ProgressivePcmBuffer::available_frames() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return available_frames_locked();
}

ProgressiveBufferDiagnostics
ProgressivePcmBuffer::diagnostics() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  ProgressiveBufferDiagnostics result;
  result.playback_frame = buffer_start_frame_ + read_offset_frames_;
  result.rendered_end_frame = rendered_end_frame_locked();
  const auto available = available_frames_locked();
  result.committed_horizon_frames =
      std::min(available, bars_to_frames(config_.committed_bars));
  result.guaranteed_rendered_horizon_frames = available;
  result.target_horizon_frames = bars_to_frames(config_.target_bars);
  result.planning_horizon_frames = bars_to_frames(config_.planning_bars);
  result.low_watermark_frames = bars_to_frames(config_.low_watermark_bars);
  result.high_watermark_frames = bars_to_frames(config_.high_watermark_bars);
  result.low_watermark_events = low_watermark_events_;
  result.neural_upgrades_applied = neural_upgrades_applied_;
  result.active_candidate_level = active_candidate_level_;
  result.below_low_watermark = available <= result.low_watermark_frames;
  result.at_or_above_high_watermark =
      available >= result.high_watermark_frames;
  result.activation_ready =
      available >= bars_to_frames(config_.guaranteed_bars);
  return result;
}

bool TransitionCandidate::valid() const noexcept {
  const auto valid_level = level == CandidateLevel::LEVEL_0 ||
                           level == CandidateLevel::LEVEL_1 ||
                           level == CandidateLevel::LEVEL_2;
  return candidate_id != 0U && generation != 0U && target_track_id != 0U &&
         valid_level && rendered_frames > 0U &&
         std::isfinite(quality_score) && technically_valid && repetition_valid;
}

std::string anonymized_debug_report(
    const TransitionDiagnosticsSnapshot &snapshot) {
  auto fallback_reason = snapshot.fallback_reason;
  std::replace(fallback_reason.begin(), fallback_reason.end(), '\n', ' ');
  std::replace(fallback_reason.begin(), fallback_reason.end(), '\r', ' ');

  std::ostringstream report;
  report << "schema=1\n"
         << "state=" << transition_state_name(snapshot.state) << '\n'
         << "natural_runway_remaining_frames="
         << snapshot.natural_runway_remaining_frames << '\n'
         << "generation_eta_seconds=" << snapshot.generation_eta_seconds << '\n'
         << "guaranteed_rendered_horizon_frames="
         << snapshot.guaranteed_rendered_horizon_frames << '\n'
         << "planning_horizon_frames=" << snapshot.planning_horizon_frames
         << '\n'
         << "committed_horizon_frames=" << snapshot.committed_horizon_frames
         << '\n'
         << "active_candidate_level="
         << static_cast<unsigned int>(snapshot.active_candidate_level) << '\n'
         << "recent_fragment_ids=";
  for (std::size_t index = 0U; index < snapshot.recent_fragment_ids.size();
       ++index) {
    if (index != 0U) {
      report << ',';
    }
    report << snapshot.recent_fragment_ids[index];
  }
  report << '\n'
         << "repetition_score=" << snapshot.repetition_score << '\n'
         << "novelty_score=" << snapshot.novelty_score << '\n'
         << "buffer_low_watermark_events="
         << snapshot.buffer_low_watermark_events << '\n'
         << "neural_upgrades_applied=" << snapshot.neural_upgrades_applied
         << '\n'
         << "missed_activation_boundaries="
         << snapshot.missed_activation_boundaries << '\n'
         << "next_activation_boundary_frame="
         << snapshot.next_activation_boundary_frame << '\n'
         << "generation=" << snapshot.generation << '\n'
         << "fallback_reason=" << fallback_reason << '\n';
  return report.str();
}

bool TransitionCoordinator::select_target(std::uint64_t track_id) {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (track_id == 0U) {
    return false;
  }
  ++generation_;
  target_track_id_ = track_id;
  has_target_ = true;
  has_deterministic_fallback_ = false;
  active_candidate_.reset();
  activation_boundaries_.clear();
  next_boundary_index_ = 0U;
  last_playback_frame_ = 0U;
  missed_activation_boundaries_ = 0U;
  fallback_reason_.clear();
  state_ = TransitionState::TARGET_SELECTED;
  return true;
}

bool TransitionCoordinator::begin_preparing() {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != TransitionState::TARGET_SELECTED || !has_target_) {
    return false;
  }
  state_ = TransitionState::PREPARING;
  return true;
}

bool TransitionCoordinator::can_publish_locked() const noexcept {
  return state_ == TransitionState::PREPARING ||
         state_ == TransitionState::FALLBACK_READY ||
         state_ == TransitionState::NEURAL_CANDIDATES_PENDING ||
         state_ == TransitionState::ARMED ||
         state_ == TransitionState::WAITING_FOR_ACTIVATION_BOUNDARY ||
         state_ == TransitionState::TRANSITIONING;
}

bool TransitionCoordinator::candidate_better_locked(
    const TransitionCandidate &candidate) const noexcept {
  if (!active_candidate_) {
    return true;
  }
  if (candidate.quality_score > active_candidate_->quality_score + 1.0e-12) {
    return true;
  }
  return std::abs(candidate.quality_score - active_candidate_->quality_score) <=
             1.0e-12 &&
         static_cast<std::uint8_t>(candidate.level) >
             static_cast<std::uint8_t>(active_candidate_->level);
}

bool TransitionCoordinator::publish_candidate(TransitionCandidate candidate) {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (!can_publish_locked() || !candidate.valid() ||
      candidate.generation != generation_ ||
      candidate.target_track_id != target_track_id_ ||
      candidate.rendered_frames < minimum_viable_frames_) {
    return false;
  }
  const auto deterministic = candidate.level != CandidateLevel::LEVEL_2;
  if (!deterministic && !has_deterministic_fallback_) {
    return false;
  }
  if (deterministic) {
    has_deterministic_fallback_ = true;
  }
  if (candidate_better_locked(candidate)) {
    active_candidate_ = candidate;
  }
  if (deterministic && state_ == TransitionState::PREPARING) {
    state_ = TransitionState::FALLBACK_READY;
  }
  return true;
}

bool TransitionCoordinator::mark_neural_candidates_pending() {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != TransitionState::FALLBACK_READY ||
      !has_deterministic_fallback_) {
    return false;
  }
  state_ = TransitionState::NEURAL_CANDIDATES_PENDING;
  return true;
}

void TransitionCoordinator::choose_next_boundary_locked(
    std::uint64_t current_playback_frame) noexcept {
  next_boundary_index_ = static_cast<std::size_t>(std::distance(
      activation_boundaries_.begin(),
      std::lower_bound(activation_boundaries_.begin(),
                       activation_boundaries_.end(),
                       current_playback_frame)));
}

bool TransitionCoordinator::arm(std::uint64_t current_playback_frame) {
  const std::lock_guard<std::mutex> lock(mutex_);
  if ((state_ != TransitionState::FALLBACK_READY &&
       state_ != TransitionState::NEURAL_CANDIDATES_PENDING) ||
      !has_deterministic_fallback_ || !active_candidate_ ||
      !active_candidate_->valid() ||
      active_candidate_->rendered_frames < minimum_viable_frames_) {
    return false;
  }
  last_playback_frame_ = current_playback_frame;
  choose_next_boundary_locked(current_playback_frame);
  state_ = TransitionState::ARMED;
  return true;
}

bool TransitionCoordinator::wait_for_activation_boundary() {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != TransitionState::ARMED ||
      next_boundary_index_ >= activation_boundaries_.size()) {
    return false;
  }
  state_ = TransitionState::WAITING_FOR_ACTIVATION_BOUNDARY;
  return true;
}

bool TransitionCoordinator::advance_playback(std::uint64_t playback_frame) {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (playback_frame < last_playback_frame_) {
    return false;
  }
  last_playback_frame_ = playback_frame;
  if (state_ != TransitionState::WAITING_FOR_ACTIVATION_BOUNDARY ||
      !active_candidate_ || !active_candidate_->valid() ||
      active_candidate_->generation != generation_ ||
      active_candidate_->target_track_id != target_track_id_ ||
      active_candidate_->rendered_frames < minimum_viable_frames_) {
    return false;
  }
  while (next_boundary_index_ < activation_boundaries_.size() &&
         activation_boundaries_[next_boundary_index_] < playback_frame) {
    ++next_boundary_index_;
    ++missed_activation_boundaries_;
  }
  if (next_boundary_index_ < activation_boundaries_.size() &&
      activation_boundaries_[next_boundary_index_] == playback_frame) {
    ++next_boundary_index_;
    state_ = TransitionState::TRANSITIONING;
    return true;
  }
  return false;
}

bool TransitionCoordinator::land() {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != TransitionState::TRANSITIONING || !active_candidate_ ||
      !active_candidate_->valid()) {
    return false;
  }
  state_ = TransitionState::LANDED;
  return true;
}

void TransitionCoordinator::cancel() {
  const std::lock_guard<std::mutex> lock(mutex_);
  ++generation_;
  state_ = TransitionState::CANCELLED;
  has_target_ = false;
  has_deterministic_fallback_ = false;
  active_candidate_.reset();
  activation_boundaries_.clear();
  next_boundary_index_ = 0U;
}

void TransitionCoordinator::fail(std::string reason) {
  const std::lock_guard<std::mutex> lock(mutex_);
  ++generation_;
  state_ = TransitionState::FAILED;
  fallback_reason_ = std::move(reason);
  has_deterministic_fallback_ = false;
  active_candidate_.reset();
}

void TransitionCoordinator::reset() {
  const std::lock_guard<std::mutex> lock(mutex_);
  ++generation_;
  state_ = TransitionState::IDLE;
  target_track_id_ = 0U;
  has_target_ = false;
  has_deterministic_fallback_ = false;
  active_candidate_.reset();
  activation_boundaries_.clear();
  next_boundary_index_ = 0U;
  last_playback_frame_ = 0U;
  missed_activation_boundaries_ = 0U;
  natural_runway_remaining_frames_ = 0U;
  generation_eta_seconds_ = 0.0;
  recent_fragment_ids_.clear();
  repetition_metrics_ = {};
  fallback_reason_.clear();
}

bool TransitionCoordinator::set_activation_boundaries(
    std::uint64_t generation, std::uint64_t target_track_id,
    std::vector<std::uint64_t> boundaries) {
  std::sort(boundaries.begin(), boundaries.end());
  boundaries.erase(std::unique(boundaries.begin(), boundaries.end()),
                   boundaries.end());
  const std::lock_guard<std::mutex> lock(mutex_);
  if (!has_target_ || generation != generation_ ||
      target_track_id != target_track_id_) {
    return false;
  }
  activation_boundaries_ = std::move(boundaries);
  choose_next_boundary_locked(last_playback_frame_);
  return true;
}

void TransitionCoordinator::set_natural_runway_remaining(
    std::uint64_t frames) noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  natural_runway_remaining_frames_ = frames;
}

void TransitionCoordinator::set_generation_eta(double seconds) noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  generation_eta_seconds_ =
      std::isfinite(seconds) ? std::max(0.0, seconds) : 0.0;
}

void TransitionCoordinator::set_recent_fragment_ids(
    std::vector<std::uint64_t> fragment_ids) {
  const std::lock_guard<std::mutex> lock(mutex_);
  recent_fragment_ids_ = std::move(fragment_ids);
}

void TransitionCoordinator::set_repetition_metrics(
    RepetitionMetrics metrics) noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  repetition_metrics_ = metrics;
}

void TransitionCoordinator::set_fallback_reason(std::string reason) {
  const std::lock_guard<std::mutex> lock(mutex_);
  fallback_reason_ = std::move(reason);
}

TransitionState TransitionCoordinator::state() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return state_;
}

std::uint64_t TransitionCoordinator::generation() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return generation_;
}

std::uint64_t TransitionCoordinator::target_track_id() const noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  return has_target_ ? target_track_id_ : 0U;
}

std::optional<TransitionCandidate>
TransitionCoordinator::active_candidate() const {
  const std::lock_guard<std::mutex> lock(mutex_);
  return active_candidate_;
}

TransitionDiagnosticsSnapshot TransitionCoordinator::diagnostics(
    const ProgressivePcmBuffer &buffer) const {
  const auto buffer_diagnostics = buffer.diagnostics();
  const std::lock_guard<std::mutex> lock(mutex_);
  TransitionDiagnosticsSnapshot result;
  result.state = state_;
  result.natural_runway_remaining_frames = natural_runway_remaining_frames_;
  result.generation_eta_seconds = generation_eta_seconds_;
  result.guaranteed_rendered_horizon_frames =
      buffer_diagnostics.guaranteed_rendered_horizon_frames;
  result.planning_horizon_frames = buffer_diagnostics.planning_horizon_frames;
  result.committed_horizon_frames =
      buffer_diagnostics.committed_horizon_frames;
  result.active_candidate_level = active_candidate_
                                      ? active_candidate_->level
                                      : buffer_diagnostics.active_candidate_level;
  result.recent_fragment_ids = recent_fragment_ids_;
  result.repetition_score = std::max(
      repetition_metrics_.exact_fragment_repeat_rate,
      std::max(repetition_metrics_.melodic_repeat_rate,
               repetition_metrics_.full_arrangement_repeat_rate));
  result.novelty_score = repetition_metrics_.novelty_per_bar;
  result.buffer_low_watermark_events =
      buffer_diagnostics.low_watermark_events;
  result.neural_upgrades_applied =
      buffer_diagnostics.neural_upgrades_applied;
  result.missed_activation_boundaries = missed_activation_boundaries_;
  result.next_activation_boundary_frame =
      next_boundary_index_ < activation_boundaries_.size()
          ? activation_boundaries_[next_boundary_index_]
          : 0U;
  result.generation = generation_;
  result.fallback_reason = fallback_reason_;
  return result;
}

} // namespace autoremix::audio
