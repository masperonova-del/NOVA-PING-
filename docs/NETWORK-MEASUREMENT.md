# Network measurement standards

## Why measurement quality matters

A single fast response does not prove a stable connection. Every result must preserve the method used, the destination and the sample count so it can be interpreted correctly.

## Metrics used by NOVA PING

| Metric | Meaning | Current calculation / reporting |
|---|---|---|
| Median | Middle response time | Median of successful HTTPS response times, in ms |
| P95 observed | High-end response time | Nearest-rank percentile; with nine samples, this is the maximum observed sample |
| Variation | Changes between responses | Mean absolute difference between consecutive successful samples, in ms |
| HTTPS request failures | Requests that did not return a successful response | Failed request count / total attempted requests |
| Cycle spread | Difference between cycle medians | Maximum cycle median minus minimum cycle median, when at least two cycles are usable |

The current variation calculation uses successful samples in their recorded order. Failed requests are not inserted as zero values. This statistic is a simple variation indicator, not a universal standardized jitter measure.

## Protocol distinctions

- **ICMP echo:** useful where permitted, but may be filtered or deprioritized.
- **TCP connect:** measures connection establishment to a specific TCP service, not pure ICMP ping.
- **HTTPS request:** includes DNS, connection setup, TLS and server response behavior unless carefully separated.
- **UDP probe:** requires a cooperative endpoint that can respond; sending UDP packets without a valid response protocol cannot establish packet loss reliably.
- **Game telemetry:** only possible when the game or platform exposes a supported measurement interface.

These measurements must never be combined into one unlabeled “ping” number.

## Current NOVA PING test design

1. Read Android's active network and validated-connectivity state.
2. Stop before testing when Android does not report validated Internet access.
3. Use three public HTTPS control endpoints.
4. For the baseline comparison, perform one preparation request and then three independent cycles with three measured HTTPS requests per endpoint in each cycle.
5. For Route Engine phase analysis, run three cycles with one fresh HTTPS connection per endpoint per cycle; rotate endpoint order between cycles.
6. Apply a bounded timeout to each DNS/TCP/TLS/HTTP probe; try each address returned by DNS until a TCP connection succeeds.
7. Aggregate DNS, TCP, TLS and HTTP status-line timings independently, using only successful samples for each phase.
8. Display median, observed P95, mean absolute variation between successful samples, and the successful/attempted count for each phase.
9. Count failure categories separately; recommendations require repeated evidence and never label an isolated failure as a network-wide fault.
10. Save the last ten completed baseline-comparison sessions locally.

## Critical distinction: request failure is not IP packet loss

The current Android implementation measures HTTPS requests. A request can fail because of DNS, TCP connection, TLS negotiation, an HTTP status, a timeout or other causes. Therefore the app reports **HTTPS request failures**, not measured IP packet loss.

A failed HTTPS request does not prove that an IP packet was lost on the path. Actual packet-loss measurement requires a suitable probe protocol and a valid responding endpoint.

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
- Android's validated-connectivity flag does not identify every possible cause of a failed request.
- A public cloud endpoint is not automatically the game server.


## Phase breakdown — audited in 1.4.1

The Route Engine opens a fresh HTTPS connection to each public control endpoint and reports these sequential phases separately:

1. **DNS resolution:** time spent resolving the endpoint hostname.
2. **TCP connection:** time to establish a TCP connection to the resolved address and service port.
3. **TLS handshake:** time to negotiate the secure connection with hostname verification enabled.
4. **HTTP status line:** elapsed time from sending the HTTP request until the complete first response status line is read. This is not a packet-level first-byte measurement.

Each destination is tested over three cycles with the destination order rotated between cycles. Phase statistics are aggregated independently: a failed TLS handshake does not erase a valid DNS or TCP sample. DNS uses Android's resolver; if it returns multiple addresses, TCP attempts them in order until one connects. The reported TCP duration is for the successful address attempt, not the sum of failed address attempts. These are not a full router-by-router path trace. They can vary with DNS caching, server load, address selection, TLS behavior and the remote service. The HTTP status code is displayed even when it is not a successful 2xx response. The P95 over only three samples is a descriptive observed percentile, not a reliable tail-latency guarantee.

The Decision Engine uses conservative, indicative thresholds to suggest what to investigate next. A single slow phase is not enough to identify the responsible network operator, prove congestion, or recommend a DNS/VPN change. Repeated measurements and controlled comparisons are required.


## Wi-Fi stability observation (1.5.0)

NOVA PING uses Android `ConnectivityManager` callbacks while the app is in the foreground:

- A Wi-Fi-specific network callback observes when Android reports a Wi-Fi network available or lost.
- Wi-Fi network capabilities are checked for `NET_CAPABILITY_VALIDATED`, which distinguishes a connected Wi-Fi network from one for which Android no longer validates Internet access.
- A default-network callback records changes in the active transport, including Wi-Fi, cellular, VPN or another transport.
- Transition timestamps are used to report the observed time until Wi-Fi or Internet validation returns.

These are operating-system network-state observations, not packet-level measurements. A lost Wi-Fi network callback does not identify whether the cause was weak signal, interference, router/repeater behavior or another radio/network event. A validation change does not prove that all Internet traffic is unreachable. Monitoring is active only while the app is in the foreground; the app does not run a hidden background service, inspect game traffic or claim to measure COD Mobile ping.


## Foreground monitor resume handling (1.5.1)

When NOVA PING returns to the foreground, it reads the current Wi-Fi and default-network snapshot before registering callbacks. An unchanged snapshot does not create a duplicate "Wi-Fi detected" event. If the state differs from the last observed state, the app labels it as a state found on resume and does not pretend to know when the change happened.

Recovery durations are only calculated when both the loss and recovery callbacks were observed during one uninterrupted foreground monitoring period. A change that happened while the app was in the background has an unknown transition time and is reported without a fabricated duration.
