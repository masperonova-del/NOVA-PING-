# NOVA BOOST — Route comparison experiment

## Objective

Build a repeatable, evidence-based comparison of the active network path before attempting any traffic-routing feature.

The first experiment compares:
- direct connectivity (VPN disconnected);
- one VPN provider/server at a time (for example, Proton VPN);
- later, an explicitly configured NOVA relay candidate, only after relay infrastructure exists.

## Controlled protocol

For each configuration:

1. Record the active transport (Wi-Fi or mobile), Android network validation state, timestamp, VPN state, and the user-entered provider/server label.
2. Use the same three HTTPS control destinations and the same timeout.
3. Run five independent samples per destination.
4. Calculate median, observed P95, mean absolute variation, completion rate, and HTTP 2xx rate.
5. Repeat the complete batch at least three times, alternating configuration order where practical.
6. Compare only batches collected close together under the same physical access network. If Wi-Fi/mobile changes, mark the pair non-comparable.

## Decision rules

A VPN candidate is not considered better merely because one sample is faster.

A candidate may be labelled "promising" only when:
- at least three paired batches are complete;
- completion rate does not regress;
- median and P95 improve consistently across batches;
- variation does not materially worsen;
- no transport change or measurement interruption invalidates the comparison.

Otherwise the result is "inconclusive" or "regression". No automatic selection is made in this phase.

## Evidence boundaries

- HTTPS control endpoints are not CODM match servers.
- These measurements compare the path to the control endpoints, not game-server latency.
- A lower HTTPS measurement does not prove a lower in-game ping.
- A VPN can add latency; a relay is useful only if its upstream route is better enough to offset the extra hop.
- The first phase does not create a VPN, change Android routing, intercept game traffic, or claim acceleration.
- The user controls VPN connection/disconnection and supplies the provider/server label; the app must not inspect another VPN application's private state.

## Implementation sequence

1. Add a comparison data model and paired-batch validity checks.
2. Add a UI flow for the user to label Direct / VPN provider / server and run the same probe plan.
3. Store comparison summaries locally with explicit timestamps and network context.
4. Add unit tests for incomplete batches, changed transport, missing samples, and false-positive wins.
5. Only after reliable evidence exists, evaluate an Android VpnService prototype and a real relay endpoint. VPN consent must be requested through Android's supported flow.
6. Test actual CODM sessions separately and record the in-game ping manually unless a documented, authorized game telemetry interface becomes available.

## Acceptance criteria

The comparison feature is complete only when it:
- refuses invalid or mismatched comparisons;
- reports median, P95, variation, and completion rate for each condition;
- identifies repeatable improvement, regression, or inconclusive evidence;
- never claims CODM ping improvement from HTTPS-only probes;
- passes unit tests and a physical-device test.
