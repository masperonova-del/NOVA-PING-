#include "metadata_tap.h"

bool np_metadata_tap_init(np_metadata_tap_t *tap)
{
    if (tap == NULL)
        return false;

    np_metadata_queue_init(&tap->queue);
    tap->ready = np_metadata_queue_is_lock_free(&tap->queue);
    return tap->ready;
}

bool np_metadata_tap_observe(np_metadata_tap_t *tap,
                             const np_packet_reader_t *reader,
                             const np_local_address_t *local_addresses,
                             size_t local_address_count)
{
    np_packet_metadata_t record;

    if (tap == NULL || !tap->ready ||
        !np_parse_packet_metadata_reader(reader, local_addresses,
                                         local_address_count, &record))
        return false;

    /* A full queue drops telemetry only; packet forwarding remains untouched. */
    return np_metadata_queue_push(&tap->queue, &record);
}

bool np_metadata_tap_poll(np_metadata_tap_t *tap,
                          np_packet_metadata_t *record)
{
    if (tap == NULL || !tap->ready)
        return false;
    return np_metadata_queue_pop(&tap->queue, record);
}

uint32_t np_metadata_tap_dropped(const np_metadata_tap_t *tap)
{
    if (tap == NULL || !tap->ready)
        return 0u;
    return np_metadata_queue_dropped(&tap->queue);
}
