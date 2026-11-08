package com.novaping.app

data class MeasurementCsvRow(
    val dataset: String,
    val timestamp: String,
    val target: String,
    val cycle: String,
    val attempt: String,
    val phase: String,
    val elapsedMs: String?,
    val httpStatus: String?,
    val failure: String?
)

object MeasurementCsv {
    private val header = listOf(
        "dataset", "timestamp", "destination", "cycle", "attempt",
        "phase", "elapsed_ms", "http_status", "failure"
    )

    fun toCsv(rows: List<MeasurementCsvRow>): String = buildString {
        appendLine(header.joinToString(",") { escape(it) })
        rows.forEach { row ->
            appendLine(listOf(
                row.dataset, row.timestamp, row.target, row.cycle, row.attempt,
                row.phase, row.elapsedMs, row.httpStatus, row.failure
            ).joinToString(",") { escape(it.orEmpty()) })
        }
    }

    private fun escape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + escaped + "\""
        } else escaped
    }
}
