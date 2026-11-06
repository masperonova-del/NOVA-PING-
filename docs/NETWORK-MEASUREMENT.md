# Network measurement standards

## Why measurement quality matters

A single fast response does not prove a stable connection. A test must collect enough samples and preserve the method used so results can be interpreted correctly.

## Required metrics

| Metric | Meaning | Reporting rule |
|---|---|---|
| Median RTT | Middle round-trip time | Report in ms with sample count |
| P95 RTT | 95th percentile round-trip time | Report to show high-latency spikes |
| Jitter | Variation between latency samples | State the exact calculation |
| Packet loss | Missing valid probes | Report sent/received counts and protocol |
| Stability | Variation across a test window | Show duration and sample count |

## Protocol distinctions

- **ICMP echo:** useful where permitted, but may be filtered or deprioritized.
- **TCP connect:** measures connection establishment to a specific TCP service, not pure ICMP ping.
- **HTTPS request:** includes DNS, connection setup, TLS and server response behavior unless carefully separated.
- **UDP probe:** requires a cooperative endpoint that can respond; sending UDP packets without a valid response protocol cannot establish packet loss reliably.
- **Game telemetry:** only possible when the game or platform exposes a supported, lawful measurement interface.

These measurements must never be combined into one unlabeled “ping” number.

## Test design

1. Record the network type and test conditions.
2. Resolve the destination and record the selected address where appropriate.
3. Warm up the connection separately from measured samples.
4. Collect a meaningful series of samples.
5. Apply timeouts consistently.
6. Calculate metrics from valid samples only.
7. Mark the test inconclusive when too few responses are available.
8. Repeat tests before comparing two destinations or configurations.

## Before/after comparison

Comparisons should use:
- the same device;
- the same access network;
- the same destination and protocol;
- similar test duration;
- comparable time and load conditions.

Show both values and the difference. Do not promise that an improvement in a diagnostic endpoint will produce the same improvement inside a game.

## False-positive prevention

- A failed HTTP request is not automatically packet loss.
- A timeout caused by a blocked protocol is not proof of a bad connection.
- A lower DNS lookup time is not proof of lower game latency.
- The nearest geographic region is not necessarily the fastest network route.
- One unusually good sample is not a stable result.
