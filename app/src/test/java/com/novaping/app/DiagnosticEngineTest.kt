package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticEngineTest {
    @Test fun reportsMissingMeasurements() {
        assertEquals("Données insuffisantes", DiagnosticEngine.assess(null, emptyList()).label)
    }

    @Test fun reportsTooFewSuccessfulSamples() {
        val metrics = PingMetrics.from(listOf(30, 35, 40, 45, 50), 25)
        assertEquals("Données insuffisantes", DiagnosticEngine.assess(metrics, listOf(35, 45)).label)
    }

    @Test fun reportsImportantLosses() {
        val metrics = PingMetrics.from((1L..15L).map { 30L + it }, 25)
        assertEquals("Échecs HTTPS importants", DiagnosticEngine.assess(metrics, listOf(35, 45, 50)).label)
    }

    @Test fun reportsHighVariation() {
        val metrics = PingMetrics.from((0 until 25).map { if (it % 2 == 0) 20L + it else 180L + it }, 25)
        assertEquals("Très variable", DiagnosticEngine.assess(metrics, listOf(30, 180, 40)).label)
    }

    @Test fun reportsModerateVariation() {
        val metrics = PingMetrics.from((0 until 25).map { if (it % 2 == 0) 20L else 100L }, 25)
        assertEquals("Variable", DiagnosticEngine.assess(metrics, listOf(30, 90, 35)).label)
    }

    @Test fun reportsConsistentMeasurements() {
        val metrics = PingMetrics.from((0 until 25).map { listOf(30L, 31L, 29L, 32L, 30L)[it % 5] }, 25)
        assertEquals("Régulier", DiagnosticEngine.assess(metrics, listOf(30, 31, 29)).label)
    }
}
