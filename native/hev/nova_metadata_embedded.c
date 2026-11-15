/*
 * Unity translation unit for the pinned HEV build.
 * These portable NOVA PING sources are copied beside this file by
 * scripts/prepare-hev-metadata.sh. Keeping them in one translation unit
 * avoids silently omitting them from upstream Android source manifests.
 */
#include "packet_metadata.c"
#include "pbuf_reader_adapter.c"
#include "metadata_tap.c"
