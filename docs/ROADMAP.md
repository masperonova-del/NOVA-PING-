# NOVA PING — Roadmap

## Phase 0 — Foundation
- [x] Create the repository.
- [x] Define product principles.
- [x] Define architecture and measurement rules.
- [ ] Select and lock the Android build stack.
- [ ] Add contribution and release conventions.

## Phase 1 — Measurement MVP
- [ ] Create the Android application shell.
- [ ] Build a test-session model.
- [ ] Implement a protocol-labeled latency probe.
- [ ] Calculate median, P95 and jitter.
- [ ] Implement valid loss accounting for supported probes.
- [ ] Display test history locally.
- [ ] Add unit tests for metric calculations.

## Phase 2 — Diagnostics
- [ ] Detect Wi-Fi/mobile network changes.
- [ ] Add stability checks.
- [ ] Compare multiple verified diagnostic endpoints.
- [ ] Explain limitations and confidence for every result.

## Phase 3 — Game intelligence
- [ ] Define game profile format.
- [ ] Add verified region metadata.
- [ ] Avoid claiming access to private game-server addresses.
- [ ] Add player-facing region comparison where evidence exists.

## Phase 4 — Recommendations
- [ ] Build a transparent recommendation engine.
- [ ] Rank actions by evidence and expected usefulness.
- [ ] Record whether a recommendation improved the measured result.

## Phase 5 — NOVA BOOST research
- [ ] Evaluate relay locations and operating costs.
- [ ] Prototype a consent-based Android VpnService.
- [ ] Test route quality against direct connectivity.
- [ ] Measure real-world benefit across networks and regions.
- [ ] Launch only if repeatable results justify it.

## Release gates

A phase is not complete until:
- code is committed;
- tests pass;
- limitations are documented;
- the feature is tested on a real Android device where required;
- user-facing claims match measured behavior.

## Current status

Only the repository foundation and technical specifications have been committed. No APK or working network booster exists yet.
