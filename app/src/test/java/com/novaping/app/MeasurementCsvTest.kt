package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementCsvTest {
    @Test
    fun writesHeaderAndRawMeasurementFields() {
        val csv = MeasurementCsv.toCsv(listOf(
            MeasurementCsvRow(
                dataset = "HTTPS",
                timestamp = "2026-11-08 18:08:01",
                target = "Google Connectivity",
                cycle = "1",
                attempt = "2",
                phase = "HTTPS",
                elapsedMs = "125",
                httpStatus = "204",
                failure = null
            )
        ))
        val lines = csv.trimEnd().lines()
        assertEquals("dataset,timestamp,destination,cycle,attempt,phase,elapsed_ms,http_status,failure", lines[0])
        assertEquals("HTTPS,2026-11-08 18:08:01,Google Connectivity,1,2,HTTPS,125,204,", lines[1])
    }

    @Test
    fun escapesCommasQuotesAndLineBreaks() {
        val csv = MeasurementCsv.toCsv(listOf(
            MeasurementCsvRow("HTTPS", "now", "Cloudflare, Inc.", "1", "1", "HTTPS", null, null, "TLS \"alert\"\nfailed")
        ))
        assertEquals("\"HTTPS\",now,\"Cloudflare, Inc.\",1,1,HTTPS,,,,\"TLS \"\"alert\"\"\nfailed\"", csv.trimEnd())
    }
}
