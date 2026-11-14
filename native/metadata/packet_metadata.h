#ifndef NOVA_PACKET_METADATA_H
#define NOVA_PACKET_METADATA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NP_METADATA_QUEUE_CAPACITY 64u
#define NP_METADATA_QUEUE_MASK (NP_METADATA_QUEUE_CAPACITY - 1u)
#define NP_MAX_LOCAL_ADDRESSES 4u

typedef enum {
    NP_ADDRESS_IPV4 = 4,
    NP_ADDRESS_IPV6 = 6
} np_address_family_t;

typedef enum {
    NP_TRANSPORT_TCP = 6,
    NP_TRANSPORT_UDP = 17
} np_transport_t;

typedef enum {
    NP_DIRECTION_OUTBOUND = 1,
    NP_DIRECTION_INBOUND = 2
} np_direction_t;

typedef struct {
    uint8_t family;
    uint8_t bytes[16];
} np_local_address_t;

/* Fixed-size metadata only. No packet buffer or payload is retained. */
typedef struct {
    uint8_t ip_version;
    uint8_t address_length;
    uint8_t transport;
    uint8_t direction;
    uint8_t remote_address[16];
    uint16_t remote_port;
    uint32_t packet_length_bytes;
    uint8_t fragmented;
} np_packet_metadata_t;

typedef struct {
    np_packet_metadata_t records[NP_METADATA_QUEUE_CAPACITY];
    _Atomic uint32_t head;
    _Atomic uint32_t tail;
    _Atomic uint32_t dropped;
} np_metadata_queue_t;

/* Returns false for malformed, unsupported, ambiguous or non-public peers. */
bool np_parse_packet_metadata(
    const uint8_t *packet,
    size_t packet_length,
    const np_local_address_t *local_addresses,
    size_t local_address_count,
    np_packet_metadata_t *output
);

/* Single-producer / single-consumer queue. Producer never waits. */
void np_metadata_queue_init(np_metadata_queue_t *queue);
bool np_metadata_queue_push(np_metadata_queue_t *queue,
                            const np_packet_metadata_t *record);
bool np_metadata_queue_pop(np_metadata_queue_t *queue,
                           np_packet_metadata_t *record);
uint32_t np_metadata_queue_dropped(const np_metadata_queue_t *queue);

#ifdef __cplusplus
}
#endif

#endif
