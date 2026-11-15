#ifndef NOVA_PBUF_READER_ADAPTER_H
#define NOVA_PBUF_READER_ADAPTER_H

#include "packet_metadata.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Matches lwIP pbuf_copy_partial semantics without importing lwIP in host tests. */
typedef uint16_t (*np_pbuf_copy_partial_fn)(const void *pbuf,
                                           void *destination,
                                           uint16_t length,
                                           uint16_t offset);

typedef struct {
    const void *pbuf;
    uint16_t total_length;
    np_pbuf_copy_partial_fn copy_partial;
} np_pbuf_reader_context_t;

/*
 * The caller keeps pbuf and context alive until parsing returns.
 * This adapter never takes ownership, retains, or modifies the pbuf.
 */
bool np_pbuf_reader_init(np_packet_reader_t *reader,
                         np_pbuf_reader_context_t *context,
                         const void *pbuf,
                         uint16_t total_length,
                         np_pbuf_copy_partial_fn copy_partial);

#ifdef __cplusplus
}
#endif

#endif
