package com.novaping.app

import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Header-only packet metadata returned by the native HEV observer. */
data class HevPacketMetadata(
    val ipVersion: Int,
    val remoteAddress: String,
    val transport: Int,
    val direction: Int,
    val remotePort: Int,
    val packetLengthBytes: Int,
    val fragmented: Boolean
)

/**
 * JNI boundary for the pinned HEV library.
 *
 * The native queue has exactly one consumer. All native polling is therefore
 * serialized through the single-thread executor in this object. This class
 * does not start a VPN, create a TUN interface, or capture payload data.
 */
object HevMetadataNative {
    private const val LIBRARY_NAME = "hev-socks5-tunnel"
    private const val RECORD_SIZE = 9
    private const val MAX_DRAIN_BATCH = 64

    private val consumer: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nova-hev-metadata-consumer").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var loadAttempted = false

    private external fun nativePoll(): LongArray?
    private external fun nativeDropped(): Long

    /** Loads the packaged HEV shared library without activating the tunnel. */
    @Synchronized
    fun isAvailable(): Boolean {
        if (libraryLoaded) return true
        if (loadAttempted) return false
        loadAttempted = true
        libraryLoaded = try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
        return libraryLoaded
    }

    /**
     * Drains at most [limit] records on the sole native queue consumer.
     * The caller receives an immutable snapshot after the queue is drained.
     */
    fun drainAvailable(limit: Int = MAX_DRAIN_BATCH): Future<List<HevPacketMetadata>> {
        require(limit in 1..MAX_DRAIN_BATCH)
        return consumer.submit<List<HevPacketMetadata>> {
            if (!isAvailable()) return@submit emptyList()
            buildList {
                for (index in 0 until limit) {
                    val raw = nativePoll() ?: break
                    decode(raw)?.let(::add)
                }
            }
        }
    }

    /** Reads the native queue's aggregate dropped-sample counter. */
    fun droppedSamples(): Future<Long> = consumer.submit<Long> {
        if (isAvailable()) nativeDropped() else 0L
    }

    internal fun decode(raw: LongArray): HevPacketMetadata? {
        if (raw.size != RECORD_SIZE) return null
        val ipVersion = raw[0].toInt()
        val addressLength = raw[1].toInt()
        val transport = raw[2].toInt()
        val direction = raw[3].toInt()
        val port = raw[6].toInt()
        val packetLength = raw[7].toInt()
        if ((ipVersion != 4 && ipVersion != 6) ||
            (ipVersion == 4 && addressLength != 4) ||
            (ipVersion == 6 && addressLength != 16) ||
            (transport != 6 && transport != 17) ||
            direction !in 1..2 ||
            port !in 1..65535 ||
            packetLength <= 0
        ) return null

        val addressBytes = ByteArray(16)
        for (index in 0 until 8) {
            addressBytes[index] =
                ((raw[4] ushr ((7 - index) * 8)) and 0xffL).toByte()
            addressBytes[index + 8] =
                ((raw[5] ushr ((7 - index) * 8)) and 0xffL).toByte()
        }
        val remoteAddress = runCatching {
            InetAddress.getByAddress(addressBytes.copyOf(addressLength)).hostAddress
        }.getOrNull() ?: return null

        return HevPacketMetadata(
            ipVersion = ipVersion,
            remoteAddress = remoteAddress,
            transport = transport,
            direction = direction,
            remotePort = port,
            packetLengthBytes = packetLength,
            fragmented = raw[8] != 0L
        )
    }
}
