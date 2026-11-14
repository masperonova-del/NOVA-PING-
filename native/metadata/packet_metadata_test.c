#include "packet_metadata.h"

#include <assert.h>
#include <stdio.h>
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

int main(void)
{
    test_ipv4_outbound_udp();
    test_ipv4_inbound_tcp();
    test_ipv6_outbound_udp();
    test_ipv6_destination_options_udp();
    test_reject_malformed_and_private_remote();
    test_reject_malformed_headers_and_fragments();
    test_first_fragment_flag();
    test_bounded_spsc_queue();
    puts("NOVA native packet metadata tests: PASS");
    return 0;
}
