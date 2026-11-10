# NOVA PING — Optimization bridge contract (1.13)

## Purpose

NOVA PING remains the measurement and verification layer. A future optimizer may propose or apply a route, but it must not rewrite, smooth, filter, or fabricate diagnostic measurements.

## Experiment sequence

1. Capture a baseline using a completed diagnostic session.
2. Pass a read-only optimization input to the optimizer.
3. Receive a recommendation and an explicit execution state.
4. If the user starts optimization, record the selected mode and route identifier.
5. Capture a second diagnostic session using the same destinations, protocol, sample plan and comparable network conditions.
6. Compare the two sessions and report gains, regressions or inconclusive evidence.

## Minimum input fields

- experiment ID and timestamp;
- selected game profile (label only unless a verified app/endpoint is available);
- transport type and Android Internet-validation state;
- destination identifier and measurement protocol;
- sample count, successes and failures;
- median, observed P95 and jitter/variation;
- phase summaries (DNS, TCP, TLS and HTTP) with their own sample counts;
- measurement limitations and confidence.

## Minimum optimizer response fields

- optimizer name and version;
- recommendation ID;
- mode: observe, recommend, ready, active, failed or stopped;
- selected route/relay identifier, if one exists;
- user-consent requirement and connection state;
- explanation and known limitations;
- timestamp and error category, if applicable.

## Comparison rules

- Compare identical destinations and protocols with identical sample plans.
- Do not compare incomplete or network-transition-interrupted sessions as if they were equivalent.
- Keep baseline and post-optimization raw samples separate.
- A better client-to-relay result is not proof of a better game-server route.
- If no verified game endpoint or in-game evidence exists, report only the control-endpoint result.
- If the evidence is weak or mixed, report inconclusive rather than a success.

## Current implementation status

This document defines the integration boundary only. The optimizer's repository, API, protocol and executable implementation have not yet been supplied on this branch. No VPN session is started and no route is changed by the bridge contract.
