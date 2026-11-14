#include "packet_metadata.h"

#include <string.h>

#define NP_IPV4_MIN_HEADER 20u
#define NP_IPV6_HEADER 40u
#define NP_TCP_MIN_HEADER 20u
#define NP_UDP_HEADER 8u
#define NP_MAX_IPV6_EXTENSIONS 8u

static uint16_t read_u16(const uint8_t *p)
{
    return (uint16_t)(((uint16_t)p[0] << 8) | (uint16_t)p[1]);
}

static bool ipv4_is_public(const uint8_t *a)
{
    if (a[0] == 0 || a[0] == 10 || a[0] == 127 || a[0] >= 224)
        return false;
    if (a[0] == 100 && (a[1] >= 64 && a[1] <= 127))
        return false;
    if (a[0] == 169 && a[1] == 254)
        return false;
    if (a[0] == 172 && (a[1] >= 16 && a[1] <= 31))
        return false;
    if (a[0] == 192 && a[1] == 168)
        return false;
    if (a[0] == 192 && a[1] == 0 && a[2] == 0)
        return false;
    if (a[0] == 192 && a[1] == 0 && a[2] == 2)
        return false;
    if (a[0] == 198 && (a[1] == 18 || a[1] == 19))
        return false;
    if (a[0] == 198 && a[1] == 51 && a[2] == 100)
        return false;
    if (a[0] == 203 && a[1] == 0 && a[2] == 113)
        return false;
    if (a[0] == 255)
        return false;
    return true;
}

static bool ipv6_is_public(const uint8_t *a)
{
    /* Global-unicast allocation 2000::/3 only. */
    if ((a[0] & 0xe0u) != 0x20u)
        return false;

    /* Documentation prefix 2001:db8::/32. */
    if (a[0] == 0x20 && a[1] == 0x01 &&
        a[2] == 0x0d && a[3] == 0xb8)
        return false;

    /* ORCHIDv1 2001:10::/28. */
    if (a[0] == 0x20 && a[1] == 0x01 &&
        a[2] == 0x00 && (a[3] & 0xf0u) == 0x10u)
        return false;

    return true;
}

static bool is_public_address(uint8_t family, const uint8_t *address)
{
    if (family == NP_ADDRESS_IPV4)
        return ipv4_is_public(address);
    if (family == NP_ADDRESS_IPV6)
        return ipv6_is_public(address);
    return false;
}

static bool address_matches_local(uint8_t family,
                                  const uint8_t *address,
                                  const np_local_address_t *locals,
                                  size_t local_count)
{
    size_t i;
    const size_t length = family == NP_ADDRESS_IPV4 ? 4u : 16u;

    for (i = 0; i < local_count; ++i) {
        if (locals[i].family == family &&
            memcmp(locals[i].bytes, address, length) == 0)
            return true;
    }
    return false;
}

static bool parse_transport(const uint8_t *packet,
                            size_t offset,
                            size_t packet_end,
                            uint8_t protocol,
                            uint8_t family,
                            const uint8_t *source,
                            const uint8_t *destination,
                            bool source_is_local,
                            bool fragmented,
                            np_packet_metadata_t *output)
{
    size_t available;
    uint16_t source_port;
    uint16_t destination_port;
    uint8_t transport;

    if (offset > packet_end)
        return false;
    available = packet_end - offset;
    if (available < NP_UDP_HEADER)
        return false;

    if (protocol == NP_TRANSPORT_TCP) {
        size_t tcp_header_length;
        if (available < NP_TCP_MIN_HEADER)
            return false;
        tcp_header_length = (size_t)(packet[offset + 12] >> 4) * 4u;
        if (tcp_header_length < NP_TCP_MIN_HEADER ||
            tcp_header_length > available)
            return false;
        transport = NP_TRANSPORT_TCP;
    } else if (protocol == NP_TRANSPORT_UDP) {
        uint16_t udp_length = read_u16(packet + offset + 4);
        if (udp_length < NP_UDP_HEADER)
            return false;
        if (!fragmented && (size_t)udp_length > available)
            return false;
        transport = NP_TRANSPORT_UDP;
    } else {
        return false;
    }

    source_port = read_u16(packet + offset);
    destination_port = read_u16(packet + offset + 2);
    if (source_port == 0 || destination_port == 0)
        return false;

    memset(output, 0, sizeof(*output));
    output->ip_version = family;
    output->address_length = family == NP_ADDRESS_IPV4 ? 4u : 16u;
    output->transport = transport;
    output->direction = source_is_local ? NP_DIRECTION_OUTBOUND
                                        : NP_DIRECTION_INBOUND;
    memcpy(output->remote_address,
           source_is_local ? destination : source,
           output->address_length);
    if (!is_public_address(family, output->remote_address))
        return false;
    output->remote_port = source_is_local ? destination_port : source_port;
    output->packet_length_bytes = (uint32_t)packet_end;
    output->fragmented = fragmented ? 1u : 0u;
    return true;
}

static bool parse_ipv4(const uint8_t *packet,
                       size_t length,
                       const np_local_address_t *locals,
                       size_t local_count,
                       np_packet_metadata_t *output)
{
    size_t header_length;
    size_t total_length;
    uint16_t fragment_field;
    bool source_local;
    bool destination_local;

    if (length < NP_IPV4_MIN_HEADER)
        return false;

    header_length = (size_t)(packet[0] & 0x0fu) * 4u;
    total_length = read_u16(packet + 2);
    if (header_length < NP_IPV4_MIN_HEADER || header_length > 60u ||
        total_length < header_length || total_length > length)
        return false;

    fragment_field = read_u16(packet + 6);
    /* Non-initial fragments do not carry the transport header. */
    if ((fragment_field & 0x1fffu) != 0)
        return false;

    source_local = address_matches_local(NP_ADDRESS_IPV4, packet + 12,
                                         locals, local_count);
    destination_local = address_matches_local(NP_ADDRESS_IPV4, packet + 16,
                                              locals, local_count);
    if (source_local == destination_local)
        return false;

    return parse_transport(packet, header_length, total_length, packet[9],
                           NP_ADDRESS_IPV4, packet + 12, packet + 16,
                           source_local, (fragment_field & 0x2000u) != 0,
                           output);
}

static bool parse_ipv6(const uint8_t *packet,
                       size_t length,
                       const np_local_address_t *locals,
                       size_t local_count,
                       np_packet_metadata_t *output)
{
    size_t packet_end;
    size_t offset = NP_IPV6_HEADER;
    uint16_t payload_length;
    uint8_t next_header;
    uint8_t extension_count = 0;
    bool fragmented = false;
    bool source_local;
    bool destination_local;

    if (length < NP_IPV6_HEADER)
        return false;

    payload_length = read_u16(packet + 4);
    /* Jumbograms are deliberately outside this metadata prototype. */
    if (payload_length == 0)
        return false;
    packet_end = NP_IPV6_HEADER + (size_t)payload_length;
    if (packet_end > length)
        return false;

    source_local = address_matches_local(NP_ADDRESS_IPV6, packet + 8,
                                         locals, local_count);
    destination_local = address_matches_local(NP_ADDRESS_IPV6, packet + 24,
                                              locals, local_count);
    if (source_local == destination_local)
        return false;

    next_header = packet[6];
    while (next_header == 0 || next_header == 43 || next_header == 60 ||
           next_header == 44 || next_header == 51) {
        uint8_t following_header;
        size_t extension_length;

        if (extension_count >= NP_MAX_IPV6_EXTENSIONS)
            return false;
        ++extension_count;

        if (next_header == 44) {
            uint16_t fragment_field;
            if (offset + 8u > packet_end)
                return false;
            following_header = packet[offset];
            fragment_field = read_u16(packet + offset + 2);
            if ((fragment_field & 0xfff8u) != 0)
                return false;
            fragmented = (fragment_field & 0x0001u) != 0;
            extension_length = 8u;
        } else {
            if (offset + 2u > packet_end)
                return false;
            following_header = packet[offset];
            if (next_header == 51)
                extension_length = ((size_t)packet[offset + 1] + 2u) * 4u;
            else
                extension_length = ((size_t)packet[offset + 1] + 1u) * 8u;
            if (extension_length < (next_header == 51 ? 12u : 8u))
                return false;
        }

        if (extension_length > packet_end - offset)
            return false;
        next_header = following_header;
        offset += extension_length;
    }

    return parse_transport(packet, offset, packet_end, next_header,
                           NP_ADDRESS_IPV6, packet + 8, packet + 24,
                           source_local, fragmented, output);
}

bool np_parse_packet_metadata(const uint8_t *packet,
                              size_t packet_length,
                              const np_local_address_t *local_addresses,
                              size_t local_address_count,
                              np_packet_metadata_t *output)
{
    uint8_t version;

    if (packet == NULL || local_addresses == NULL || output == NULL ||
        packet_length == 0 || local_address_count == 0 ||
        local_address_count > NP_MAX_LOCAL_ADDRESSES)
        return false;

    version = packet[0] >> 4;
    if (version == 4)
        return parse_ipv4(packet, packet_length, local_addresses,
                          local_address_count, output);
    if (version == 6)
        return parse_ipv6(packet, packet_length, local_addresses,
                          local_address_count, output);
    return false;
}

void np_metadata_queue_init(np_metadata_queue_t *queue)
{
    if (queue == NULL)
        return;
    atomic_init(&queue->head, 0u);
    atomic_init(&queue->tail, 0u);
    atomic_init(&queue->dropped, 0u);
}

bool np_metadata_queue_push(np_metadata_queue_t *queue,
                            const np_packet_metadata_t *record)
{
    uint32_t head;
    uint32_t tail;

    if (queue == NULL || record == NULL)
        return false;

    head = atomic_load_explicit(&queue->head, memory_order_relaxed);
    tail = atomic_load_explicit(&queue->tail, memory_order_acquire);
    if ((uint32_t)(head - tail) >= NP_METADATA_QUEUE_CAPACITY) {
        atomic_fetch_add_explicit(&queue->dropped, 1u, memory_order_relaxed);
        return false;
    }

    queue->records[head & NP_METADATA_QUEUE_MASK] = *record;
    atomic_store_explicit(&queue->head, head + 1u, memory_order_release);
    return true;
}

bool np_metadata_queue_pop(np_metadata_queue_t *queue,
                           np_packet_metadata_t *record)
{
    uint32_t tail;
    uint32_t head;

    if (queue == NULL || record == NULL)
        return false;

    tail = atomic_load_explicit(&queue->tail, memory_order_relaxed);
    head = atomic_load_explicit(&queue->head, memory_order_acquire);
    if (tail == head)
        return false;

    *record = queue->records[tail & NP_METADATA_QUEUE_MASK];
    atomic_store_explicit(&queue->tail, tail + 1u, memory_order_release);
    return true;
}

uint32_t np_metadata_queue_dropped(const np_metadata_queue_t *queue)
{
    if (queue == NULL)
        return 0u;
    return atomic_load_explicit(&queue->dropped, memory_order_relaxed);
}
