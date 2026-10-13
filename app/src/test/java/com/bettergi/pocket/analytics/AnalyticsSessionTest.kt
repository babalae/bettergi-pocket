package com.bettergi.pocket.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsSessionTest {
    @Test
    fun missingAppKeyPreventsSdkCallsEvenWhenTrackingIsRequested() {
        val backend = RecordingBackend()
        val session = AnalyticsSession(configured = false, backend)

        assertFalse(session.start())
        session.pageStart("panel")
        session.event("feature_toggle", "true")
        session.pageEnd("panel")

        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun noSdkCallsBeforeStart() {
        val backend = RecordingBackend()
        val session = AnalyticsSession(configured = true, backend)

        session.pageStart("panel")
        session.event("feature_toggle", "true")
        session.pageEnd("panel")

        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun startStartsOnceAndPairsOnlyOpenedPages() {
        val backend = RecordingBackend()
        val session = AnalyticsSession(configured = true, backend)

        assertTrue(session.start())
        assertTrue(session.start())
        session.pageEnd("panel")
        session.pageStart("panel")
        session.pageStart("panel")
        session.event("feature_toggle", "true")
        session.pageEnd("panel")
        session.pageEnd("panel")

        assertEquals(listOf("start", "pageStart:panel", "event:feature_toggle:true", "pageEnd:panel"),
            backend.calls)
    }

    private class RecordingBackend : AnalyticsBackend {
        val calls = mutableListOf<String>()
        override fun start() { calls += "start" }
        override fun pageStart(name: String) { calls += "pageStart:$name" }
        override fun pageEnd(name: String) { calls += "pageEnd:$name" }
        override fun event(id: String, label: String) { calls += "event:$id:$label" }
    }
}
