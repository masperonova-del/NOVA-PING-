package com.novaping.app

/** Small in-memory positive-answer cache. It never caches failures or empty DNS answers. */
internal class DnsAnswerCache(
    private val maxTtlSeconds: Long = 86_400,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class Key(val hostname: String, val type: DnsRecordType)
    private data class Entry(val answers: List<DnsAnswer>, val expiresAtMillis: Long)
    private val entries = HashMap<Key, Entry>()

    init {
        require(maxTtlSeconds in 1..604_800)
    }

    @Synchronized
    fun put(hostname: String, type: DnsRecordType, answers: List<DnsAnswer>) {
        val normalized = runCatching { DnsWireCodec.normalizeHostname(hostname) }.getOrNull() ?: return
        if (answers.isEmpty() || answers.any { it.type != type }) return
        val ttl = minOf(maxTtlSeconds, answers.minOf { it.ttlSeconds })
        if (ttl <= 0) return
        val now = nowMillis()
        val ttlMillis = ttl.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
        val expiry = if (Long.MAX_VALUE - now < ttlMillis) Long.MAX_VALUE else now + ttlMillis
        val safeAnswers = answers.map { it.copy(addressBytes = it.addressBytes.copyOf()) }
        entries[Key(normalized, type)] = Entry(safeAnswers, expiry)
    }

    @Synchronized
    fun get(hostname: String, type: DnsRecordType): List<DnsAnswer>? {
        val normalized = runCatching { DnsWireCodec.normalizeHostname(hostname) }.getOrNull() ?: return null
        val key = Key(normalized, type)
        val entry = entries[key] ?: return null
        if (nowMillis() >= entry.expiresAtMillis) {
            entries.remove(key)
            return null
        }
        return entry.answers.map { it.copy(addressBytes = it.addressBytes.copyOf()) }
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
