#ifndef NOVA_METADATA_TAP_H
#define NOVA_METADATA_TAP_H

#include "packet_metadata.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    np_metadata_queue_t queue;
    bool ready;
} np_metadata_tap_t;

/* Initializes the bounded queue and verifies lock-free atomics on this ABI. */
bool np_metadata_tap_init(np_metadata_tap_t *tap);

/*
 * Synchronous, allocation-free observation. It copies only validated headers
 * through the reader, then queues fixed metadata. It never owns or mutates the
 * source packet. False means the sample was rejected or the queue was full.
 */
bool np_metadata_tap_observe(np_metadata_tap_t *tap,
                             const np_packet_reader_t *reader,
                             const np_local_address_t *local_addresses,
                             size_t local_address_count);

/* Intended for the single consumer, outside the packet-transfer path. */
bool np_metadata_tap_poll(np_metadata_tap_t *tap,
                          np_packet_metadata_t *record);
uint32_t np_metadata_tap_dropped(const np_metadata_tap_t *tap);

#ifdef __cplusplus
}
#endif

#endif
