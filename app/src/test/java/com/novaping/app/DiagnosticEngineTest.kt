package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticEngineTest {
    @Test fun reportsMissingMeasurements() {
        assertEquals("Données insuffisantes", DiagnosticEngine.assess(null, emptyList()).label)
    }

    @Test fun reportsTooFewSuccessfulSamples() {
        val metrics = PingMetrics.from(listOf(30, 35, 40, 45, 50), 9)
        assertEquals("Données insuffisantes", DiagnosticEngine.assess(metrics, listOf(35, 45)).label)
    }

    @Test fun reportsImportantLosses() {
        val metrics = PingMetrics.from(listOf(30, 35, 40, 45, 50, 55), 9)
        assertEquals("Pertes importantes", DiagnosticEngine.assess(metrics, listOf(35, 45, 50)).label)
    }

    @Test fun reportsHighVariation() {
        val metrics = PingMetrics.from(listOf(20, 180, 30, 190, 25, 170, 35, 185, 40), 9)
        assertEquals("Très variable", DiagnosticEngine.assess(metrics, listOf(30, 180, 40)).label)
    }

    @Test fun reportsModerateVariation() {
        val metrics = PingMetrics.from(listOf(20, 100, 20, 100, 20, 100, 20, 100, 20), 9)
        assertEquals("Variable", DiagnosticEngine.assess(metrics, listOf(30, 90, 35)).label)
    }

    @Test fun reportsConsistentMeasurements() {
        val metrics = PingMetrics.from(listOf(30, 31, 29, 32, 30, 31, 29, 30, 31), 9)
        assertEquals("Régulier", DiagnosticEngine.assess(metrics, listOf(30, 31, 29)).label)
    }
}
