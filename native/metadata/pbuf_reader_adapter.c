#include "pbuf_reader_adapter.h"

#include <limits.h>

static bool pbuf_reader_copy(void *opaque,
                             size_t offset,
                             uint8_t *destination,
                             size_t length)
{
    np_pbuf_reader_context_t *context = opaque;
    uint16_t copied;

    if (context == NULL || context->pbuf == NULL ||
        context->copy_partial == NULL ||
        offset > context->total_length ||
        length > (size_t)context->total_length - offset ||
        offset > UINT16_MAX || length > UINT16_MAX ||
        (length > 0u && destination == NULL))
        return false;

    if (length == 0u)
        return true;

    copied = context->copy_partial(context->pbuf, destination,
                                   (uint16_t)length, (uint16_t)offset);
    return copied == (uint16_t)length;
}

bool np_pbuf_reader_init(np_packet_reader_t *reader,
                         np_pbuf_reader_context_t *context,
                         const void *pbuf,
                         uint16_t total_length,
                         np_pbuf_copy_partial_fn copy_partial)
{
    if (reader == NULL || context == NULL || pbuf == NULL ||
        total_length == 0u || copy_partial == NULL)
        return false;

    context->pbuf = pbuf;
    context->total_length = total_length;
    context->copy_partial = copy_partial;

    reader->segments = NULL;
    reader->segment_count = 0u;
    reader->total_length = total_length;
    reader->copy = pbuf_reader_copy;
    reader->copy_context = context;
    return true;
}
