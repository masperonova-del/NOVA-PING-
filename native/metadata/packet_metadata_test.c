#include "packet_metadata.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void test_ipv4_outbound_udp(void)
{
    const uint8_t packet[28] = {
        0x45, 0x00, 0x00, 0x1c, 0x00, 0x01, 0x00, 0x00,
        64, 17, 0, 0, 10, 0, 0, 2, 8, 8, 8, 8,
        0xc3, 0x50, 0x00, 0x35, 0x00, 0x08, 0, 0
    };
    np_local_address_t local = { .family = NP_ADDRESS_IPV4,
                                 .bytes = { 10, 0, 0, 2 } };
    np_packet_metadata_t result;

    assert(np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
    assert(result.ip_version == 4);
    assert(result.transport == NP_TRANSPORT_UDP);
    assert(result.direction == NP_DIRECTION_OUTBOUND);
    assert(result.remote_port == 53);
    assert(result.packet_length_bytes == sizeof(packet));
    assert(result.address_length == 4);
    assert(memcmp(result.remote_address, "\x08\x08\x08\x08", 4) == 0);
}

static void test_ipv4_inbound_tcp(void)
{
    uint8_t packet[40] = {
        0x45, 0x00, 0x00, 0x28, 0, 0, 0, 0, 64, 6, 0, 0,
        1, 1, 1, 1, 10, 0, 0, 2,
        0x01, 0xbb, 0x9c, 0x40, 0, 0, 0, 1, 0, 0, 0, 0,
        0x50, 0x18, 0x10, 0, 0, 0, 0, 0
    };
    np_local_address_t local = { .family = NP_ADDRESS_IPV4,
                                 .bytes = { 10, 0, 0, 2 } };
    np_packet_metadata_t result;

    assert(np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
    assert(result.transport == NP_TRANSPORT_TCP);
    assert(result.direction == NP_DIRECTION_INBOUND);
    assert(result.remote_port == 443);
    assert(result.remote_address[0] == 1 && result.remote_address[3] == 1);
}

static void test_ipv6_outbound_udp(void)
{
    uint8_t packet[48] = { 0 };
    np_local_address_t local = { .family = NP_ADDRESS_IPV6,
                                 .bytes = { 0xfd, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 1 } };
    np_packet_metadata_t result;

    packet[0] = 0x60;
    packet[4] = 0;
    packet[5] = 8;
    packet[6] = 17;
    packet[7] = 64;
    memcpy(packet + 8, local.bytes, 16);
    packet[24] = 0x26;
    packet[25] = 0x06;
    packet[26] = 0x47;
    packet[27] = 0x00;
    packet[39] = 0x11;
    packet[40] = 0xc3;
    packet[41] = 0x50;
    packet[42] = 0x01;
    packet[43] = 0xbb;
    packet[44] = 0;
    packet[45] = 8;

    assert(np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
    assert(result.ip_version == 6);
    assert(result.address_length == 16);
    assert(result.transport == NP_TRANSPORT_UDP);
    assert(result.remote_port == 443);
    assert(result.direction == NP_DIRECTION_OUTBOUND);

    /* ORCHIDv2 2001:20::/28 is not a public endpoint candidate. */
    packet[24] = 0x20; packet[25] = 0x01;
    packet[26] = 0x00; packet[27] = 0x20;
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
}

static void test_ipv6_destination_options_udp(void)
{
    uint8_t packet[56] = { 0 };
    np_local_address_t local = { .family = NP_ADDRESS_IPV6,
                                 .bytes = { 0xfd, 0, 0, 0x44, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 2 } };
    np_packet_metadata_t result;
    const uint8_t expected_remote[16] = {
        0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0,
        0, 0, 0, 0, 0, 0, 0x11, 0x11
    };

    packet[0] = 0x60;
    packet[4] = 0;
    packet[5] = 16;
    packet[6] = 60; /* Destination Options */
    packet[7] = 64;
    memcpy(packet + 8, local.bytes, 16);
    memcpy(packet + 24, expected_remote, sizeof(expected_remote));
    packet[40] = 17; /* UDP follows the extension header */
    packet[41] = 0;  /* Eight-byte extension header */
    packet[48] = 0xcf;
    packet[49] = 0x08; /* 53000 */
    packet[50] = 0x1d;
    packet[51] = 0x4c; /* 7500 */
    packet[53] = 8;

    assert(np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
    assert(result.ip_version == 6);
    assert(result.address_length == 16);
    assert(result.transport == NP_TRANSPORT_UDP);
    assert(result.direction == NP_DIRECTION_OUTBOUND);
    assert(result.remote_port == 7500);
    assert(result.packet_length_bytes == sizeof(packet));
    assert(memcmp(result.remote_address, expected_remote, sizeof(expected_remote)) == 0);
}

static void test_reject_malformed_and_private_remote(void)
{
    const uint8_t short_packet[8] = { 0x45 };
    uint8_t packet[28] = {
        0x45, 0, 0, 0x1c, 0, 0, 0, 0, 64, 17, 0, 0,
        10, 0, 0, 2, 192, 168, 1, 1,
        0xc3, 0x50, 0, 53, 0, 8, 0, 0
    };
    np_local_address_t local = { .family = NP_ADDRESS_IPV4,
                                 .bytes = { 10, 0, 0, 2 } };
    np_packet_metadata_t result;

    assert(!np_parse_packet_metadata(short_packet, sizeof(short_packet),
                                      &local, 1, &result));
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));

    /* Deprecated 6to4 relay anycast is not public endpoint evidence. */
    packet[16] = 192; packet[17] = 88; packet[18] = 99; packet[19] = 1;
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
}

static void test_reject_malformed_headers_and_fragments(void)
{
    uint8_t packet[40] = {
        0x45, 0, 0, 0x28, 0, 0, 0, 0, 64, 6, 0, 0,
        10, 0, 0, 2, 1, 1, 1, 1,
        0x9c, 0x40, 0x01, 0xbb, 0, 0, 0, 1, 0, 0, 0, 0,
        0x50, 0x18, 0x10, 0, 0, 0, 0, 0
    };
    np_local_address_t local = { .family = NP_ADDRESS_IPV4,
                                 .bytes = { 10, 0, 0, 2 } };
    np_packet_metadata_t result;

    /* Invalid IPv4 IHL and total-length overflow. */
    packet[0] = 0x44;
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
    packet[0] = 0x45;
    packet[2] = 0x01; packet[3] = 0x00;
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));

    /* Invalid TCP data offset and unsupported IP protocol. */
    packet[2] = 0; packet[3] = 0x28;
    packet[32] = 0x40;
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
    packet[32] = 0x50;
    packet[9] = 1;
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));

    /* A non-initial IPv4 fragment has no transport header to inspect. */
    packet[9] = 6;
    packet[6] = 0; packet[7] = 1;
    assert(!np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
}

static void test_first_fragment_flag(void)
{
    uint8_t packet[28] = {
        0x45, 0, 0, 0x1c, 0, 0, 0x20, 0, 64, 17, 0, 0,
        10, 0, 0, 2, 8, 8, 4, 4,
        0xc3, 0x50, 0, 53, 0, 0x20, 0, 0
    };
    np_local_address_t local = { .family = NP_ADDRESS_IPV4,
                                 .bytes = { 10, 0, 0, 2 } };
    np_packet_metadata_t result;

    assert(np_parse_packet_metadata(packet, sizeof(packet), &local, 1, &result));
    assert(result.fragmented == 1);
}

static void test_bounded_spsc_queue(void)
{
    np_metadata_queue_t queue;
    np_packet_metadata_t input = { .ip_version = 4, .remote_port = 443 };
    np_packet_metadata_t output;
    uint32_t i;

    np_metadata_queue_init(&queue);
    assert(np_metadata_queue_is_lock_free(&queue));
    for (i = 0; i < NP_METADATA_QUEUE_CAPACITY; ++i) {
        input.remote_port = (uint16_t)(1000u + i);
        assert(np_metadata_queue_push(&queue, &input));
    }
    assert(!np_metadata_queue_push(&queue, &input));
    assert(np_metadata_queue_dropped(&queue) == 1u);

    for (i = 0; i < NP_METADATA_QUEUE_CAPACITY; ++i) {
        assert(np_metadata_queue_pop(&queue, &output));
        assert(output.remote_port == (uint16_t)(1000u + i));
    }
    assert(!np_metadata_queue_pop(&queue, &output));
}



static size_t decode_hex(const char *text, uint8_t *output, size_t capacity)
{
    size_t length = strlen(text);
    size_t i;
    if ((length % 2u) != 0u || length / 2u > capacity)
        return 0u;
    for (i = 0; i < length / 2u; ++i) {
        char pair[3] = { text[i * 2u], text[i * 2u + 1u], '\0' };
        char *end = NULL;
        unsigned long value = strtoul(pair, &end, 16);
        if (end == pair || *end != '\0' || value > 255u)
            return 0u;
        output[i] = (uint8_t)value;
    }
    return length / 2u;
}

static unsigned long parse_number(const char *text)
{
    char *end = NULL;
    unsigned long value = strtoul(text, &end, 10);
    assert(end != text && *end == '\0');
    return value;
}

static void test_shared_packet_metadata_vectors(const char *path)
{
    FILE *file = fopen(path, "r");
    char line[8192];
    unsigned int tested = 0u;

    assert(file != NULL);
    while (fgets(line, sizeof(line), file) != NULL) {
        char *fields[11];
        char *cursor = line;
        uint8_t local_bytes[16] = { 0 };
        uint8_t packet[2048] = { 0 };
        uint8_t expected_remote[16] = { 0 };
        size_t local_length;
        size_t packet_length;
        size_t remote_length = 0u;
        size_t i;
        np_local_address_t local = { 0 };
        np_packet_metadata_t result;
        bool accepted;

        if (line[0] == '#' || line[0] == '\n' || line[0] == '\r')
            continue;
        line[strcspn(line, "\r\n")] = '\0';
        for (i = 0; i < 11u; ++i) {
            char *separator;
            fields[i] = cursor;
            separator = strchr(cursor, '\t');
            if (i < 10u) {
                assert(separator != NULL);
                *separator = '\0';
                cursor = separator + 1;
            } else if (separator != NULL) {
                *separator = '\0';
            }
        }

        local_length = decode_hex(fields[1], local_bytes, sizeof(local_bytes));
        packet_length = decode_hex(fields[2], packet, sizeof(packet));
        assert((local_length == 4u || local_length == 16u) && packet_length > 0u);
        local.family = local_length == 4u ? NP_ADDRESS_IPV4 : NP_ADDRESS_IPV6;
        memcpy(local.bytes, local_bytes, local_length);

        accepted = np_parse_packet_metadata(packet, packet_length, &local, 1u, &result);
        if (strcmp(fields[3], "reject") == 0) {
            assert(!accepted);
        } else {
            assert(strcmp(fields[3], "accept") == 0 && accepted);
            remote_length = decode_hex(fields[5], expected_remote, sizeof(expected_remote));
            assert(result.ip_version == parse_number(fields[4]));
            assert(result.address_length == remote_length);
            assert(memcmp(result.remote_address, expected_remote, remote_length) == 0);
            assert(result.remote_port == parse_number(fields[6]));
            assert(result.transport == (strcmp(fields[7], "TCP") == 0
                ? NP_TRANSPORT_TCP : NP_TRANSPORT_UDP));
            assert(result.direction == (strcmp(fields[8], "OUTBOUND") == 0
                ? NP_DIRECTION_OUTBOUND : NP_DIRECTION_INBOUND));
            assert(result.packet_length_bytes == parse_number(fields[9]));
            assert(result.fragmented == parse_number(fields[10]));
        }
        ++tested;
    }
    assert(fclose(file) == 0);
    assert(tested >= 8u);
    printf("Shared packet metadata vectors: %u passed\n", tested);
}


static void test_packet_reader_crosses_segments_without_copying_payload(void)
{
    const uint8_t first[] = { 0x45, 0x00, 0x00 };
    const uint8_t second[] = { 0x28, 0x00 };
    const uint8_t third[] = { 0x01, 0x02, 0x03, 0xaa, 0xbb, 0xcc };
    const np_packet_segment_t segments[] = {
        { first, sizeof(first) },
        { second, sizeof(second) },
        { third, sizeof(third) }
    };
    const np_packet_reader_t reader = {
        segments, sizeof(segments) / sizeof(segments[0]),
        sizeof(first) + sizeof(second) + sizeof(third)
    };
    uint8_t header[8] = { 0 };
    const uint8_t expected[] = { 0x00, 0x28, 0x00, 0x01, 0x02, 0x03 };

    /* Read across all segment boundaries, without requesting trailing payload. */
    assert(np_packet_reader_copy(&reader, 2u, header, sizeof(header)));
    assert(memcmp(header, (const uint8_t[]){0x00,0x28,0x00,0x01,0x02,0x03,0xaa,0xbb}, 8u) == 0);
    assert(np_packet_reader_copy(&reader, 2u, header, sizeof(expected)));
    assert(memcmp(header, expected, sizeof(expected)) == 0);

    /* Out-of-range reads and inconsistent aggregate lengths fail closed. */
    assert(!np_packet_reader_copy(&reader, reader.total_length - 1u, header, 2u));
    {
        np_packet_reader_t invalid = reader;
        invalid.total_length--;
        assert(!np_packet_reader_copy(&invalid, 0u, header, 1u));
    }
    {
        const np_packet_segment_t invalid_segments[] = {
            { NULL, 1u }
        };
        const np_packet_reader_t invalid = { invalid_segments, 1u, 1u };
        assert(!np_packet_reader_copy(&invalid, 0u, header, 1u));
    }
}

int main(int argc, char **argv)
{
    test_ipv4_outbound_udp();
    test_ipv4_inbound_tcp();
    test_ipv6_outbound_udp();
    test_ipv6_destination_options_udp();
    test_reject_malformed_and_private_remote();
    test_reject_malformed_headers_and_fragments();
    test_first_fragment_flag();
    test_bounded_spsc_queue();
    test_packet_reader_crosses_segments_without_copying_payload();
    if (argc > 1) test_shared_packet_metadata_vectors(argv[1]);
    puts("NOVA native packet metadata tests: PASS");
    return 0;
}
