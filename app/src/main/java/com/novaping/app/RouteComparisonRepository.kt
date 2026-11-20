package com.novaping.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class RouteComparisonPath { DIRECT, CANDIDATE }

/**
 * Local-only storage for the three DIRECT and three CANDIDATE batches of one
 * comparison experiment. Raw packet data, endpoint payloads, SSIDs and MAC
 * addresses are not stored.
 */
class RouteComparisonRepository(private val preferences: SharedPreferences) {
    companion object {
        private const val KEY_EXPERIMENT_ID = "route_comparison_experiment_id"
        private const val KEY_DIRECT = "route_comparison_direct_batches"
        private const val KEY_CANDIDATE = "route_comparison_candidate_batches"
        private const val MAX_BATCHES_PER_PATH = 3
    }

    @Synchronized
    fun experimentId(): String {
        val existing = preferences.getString(KEY_EXPERIMENT_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_EXPERIMENT_ID, created).apply()
        return created
    }

    @Synchronized
    fun save(path: RouteComparisonPath, batch: RouteComparisonBatch) {
        require(batch.experimentId == experimentId()) {
            "Le lot appartient à une autre expérience."
        }
        val key = keyFor(path)
        val current = readArray(key)
        val next = JSONArray()
        val start = (current.length() - (MAX_BATCHES_PER_PATH - 1)).coerceAtLeast(0)
        for (index in start until current.length()) {
            val item = current.optJSONObject(index) ?: continue
            next.put(item)
        }
        next.put(encode(batch))
        preferences.edit().putString(key, next.toString()).apply()
    }

    @Synchronized
    fun batches(path: RouteComparisonPath): List<RouteComparisonBatch> {
        val array = readArray(keyFor(path))
        return (0 until array.length()).mapNotNull { index ->
            runCatching { decode(array.getJSONObject(index)) }.getOrNull()
        }
    }

    @Synchronized
    fun reset() {
        preferences.edit()
            .remove(KEY_DIRECT)
            .remove(KEY_CANDIDATE)
            .putString(KEY_EXPERIMENT_ID, UUID.randomUUID().toString())
            .apply()
    }

    private fun keyFor(path: RouteComparisonPath): String =
        if (path == RouteComparisonPath.DIRECT) KEY_DIRECT else KEY_CANDIDATE

    private fun readArray(key: String): JSONArray =
        runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrDefault(JSONArray())

    private fun encode(batch: RouteComparisonBatch) = JSONObject().apply {
        put("label", batch.label)
        put("transport", batch.transport.name)
        put("vpnLabel", batch.vpnLabel ?: JSONObject.NULL)
        put("experimentId", batch.experimentId)
        put("batchId", batch.batchId)
        put("networkFingerprint", batch.networkFingerprint)
        put("startedAtEpochMs", batch.startedAtEpochMs)
        put("completedAtEpochMs", batch.completedAtEpochMs)
        put("measurementProfile", batch.measurementProfile)
        put("targetFingerprint", batch.targetFingerprint)
        put("aggregates", JSONArray().apply {
            batch.aggregates.forEach { aggregate ->
                put(JSONObject().apply {
                    put("host", aggregate.host)
                    put("sampleCount", aggregate.sampleCount)
                    put("completeSamples", aggregate.completeSamples)
                    put("httpSuccessfulSamples", aggregate.httpSuccessfulSamples)
                    putPhase("dns", aggregate.dns)
                    putPhase("tcp", aggregate.tcp)
                    putPhase("tcpAttemptTotal", aggregate.tcpAttemptTotal)
                    putPhase("tls", aggregate.tls)
                    putPhase("httpStatusLine", aggregate.httpStatusLine)
                })
            }
        })
    }

    private fun JSONObject.putPhase(key: String, phase: PhaseStatistics) {
        put(key, JSONObject().apply {
            put("medianMs", phase.medianMs ?: JSONObject.NULL)
            put("p95ObservedMs", phase.p95ObservedMs ?: JSONObject.NULL)
            put("meanAbsoluteVariationMs", phase.meanAbsoluteVariationMs ?: JSONObject.NULL)
            put("successfulSamples", phase.successfulSamples)
            put("attemptedSamples", phase.attemptedSamples)
        })
    }

    private fun decode(json: JSONObject): RouteComparisonBatch {
        val aggregatesJson = json.getJSONArray("aggregates")
        val aggregates = (0 until aggregatesJson.length()).map { index ->
            val item = aggregatesJson.getJSONObject(index)
            RouteAggregate(
                host = item.getString("host"),
                sampleCount = item.getInt("sampleCount"),
                completeSamples = item.getInt("completeSamples"),
                httpSuccessfulSamples = item.getInt("httpSuccessfulSamples"),
                dns = item.readPhase("dns"),
                tcp = item.readPhase("tcp"),
                tcpAttemptTotal = item.readPhase("tcpAttemptTotal"),
                tls = item.readPhase("tls"),
                httpStatusLine = item.readPhase("httpStatusLine"),
                failures = emptyMap(),
                httpStatuses = emptyMap()
            )
        }
        return RouteComparisonBatch(
            label = json.getString("label"),
            transport = NetworkTransport.valueOf(json.getString("transport")),
            vpnLabel = if (json.isNull("vpnLabel")) null else json.getString("vpnLabel"),
            aggregates = aggregates,
            experimentId = json.getString("experimentId"),
            batchId = json.getString("batchId"),
            networkFingerprint = json.getString("networkFingerprint"),
            startedAtEpochMs = json.getLong("startedAtEpochMs"),
            completedAtEpochMs = json.getLong("completedAtEpochMs"),
            measurementProfile = json.getString("measurementProfile"),
            targetFingerprint = json.getString("targetFingerprint")
        )
    }

    private fun JSONObject.readPhase(key: String): PhaseStatistics {
        val phase = getJSONObject(key)
        return PhaseStatistics(
            medianMs = if (phase.isNull("medianMs")) null else phase.getLong("medianMs"),
            p95ObservedMs = if (phase.isNull("p95ObservedMs")) null else phase.getLong("p95ObservedMs"),
            meanAbsoluteVariationMs = if (phase.isNull("meanAbsoluteVariationMs")) null else phase.getLong("meanAbsoluteVariationMs"),
            successfulSamples = phase.getInt("successfulSamples"),
            attemptedSamples = phase.getInt("attemptedSamples")
        )
    }
}
