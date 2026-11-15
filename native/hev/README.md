# HEV metadata integration

This directory contains the first native integration layer for the exact upstream
HEV revision `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`.

## What it changes

- Observes the TUN-input pbuf in `lwip_io_task_entry()` before compaction.
- Observes the TUN-output pbuf in `netif_output_handler()` before writing.
- Uses a typed wrapper around lwIP `pbuf_copy_partial()`; the portable parser
  reads only the IP and transport headers it needs.
- Builds the local-address list from HEV's configured TUN IPv4/IPv6 addresses,
  never from the internal loopback netif addresses.
- Drops telemetry on parser failure, unsupported input, non-lock-free atomics,
  or queue saturation. The original pbuf and tunnel result are not changed.
- Exposes a single-consumer native poll function and a dropped-sample counter.
  JNI/Kotlin polling is deliberately not wired until one dedicated consumer
  executor is established.

The portable C sources are included through a small unity translation unit
because this integration is applied to an upstream HEV source tree whose
Android source manifest is not modified by this patch. This is a build bridge,
not a claim that an AAR has already been produced.

## Apply and validate

Run:

```bash
bash scripts/prepare-hev-metadata.sh
```

The script checks out the pinned HEV revision, stages the already-tested
portable metadata sources, and verifies that the patch applies cleanly.

## Important limits

This step creates the native hook integration source patch. It does not by
itself build or replace the Android AAR, wire JNI polling into Kotlin, or enable
a VPN. Before shipping, the patched HEV source must be compiled in the actual
Android build, and the metadata queue must be drained by exactly one consumer.
