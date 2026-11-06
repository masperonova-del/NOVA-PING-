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
4. Perform one preparation request before the measured cycles for each endpoint.
5. Run three independent cycles, with three measured HTTPS requests per endpoint in each cycle.
6. Apply the same request timeout to each probe.
7. Calculate statistics only from successful responses.
8. Classify known failures such as DNS resolution, timeout, TLS, connection and HTTP status errors.
9. Mark the result insufficient when too few successful responses are available.
10. Save the last ten completed sessions locally.

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


## Phase breakdown added in 1.4.0

The Route Engine opens a fresh HTTPS connection to each public control endpoint and reports these sequential phases separately:

1. **DNS resolution:** time spent resolving the endpoint hostname.
2. **TCP connection:** time to establish a TCP connection to the resolved address and service port.
3. **TLS handshake:** time to negotiate the secure connection with hostname verification enabled.
4. **HTTP first header:** time from sending the HTTP request until the first response status line is received.

These are separate phase timings from one connection attempt, not a full router-by-router path trace. They can vary with DNS caching, server load, address selection, TLS behavior and the remote service. The HTTP status code is displayed even when it is not a successful 2xx response.

The Decision Engine uses conservative, indicative thresholds to suggest what to investigate next. A single slow phase is not enough to identify the responsible network operator, prove congestion, or recommend a DNS/VPN change. Repeated measurements and controlled comparisons are required.
