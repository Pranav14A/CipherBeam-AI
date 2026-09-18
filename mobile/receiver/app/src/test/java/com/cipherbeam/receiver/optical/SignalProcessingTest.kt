package com.cipherbeam.receiver.optical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalProcessingTest {

    @Test
    fun rednessScoreIsBrightnessIndependent() {
        assertTrue(
            RednessScorer.score(70, 210) >
                    RednessScorer.score(100, 180)
        )
    }

    @Test
    fun greenScoreDetectsGreenDominance() {
        val green = RednessScorer.greenScore(
            u = 80,
            v = 220
        )

        val nonGreen = RednessScorer.greenScore(
            u = 180,
            v = 120
        )

        assertTrue(green < nonGreen)
    }

    @Test
    fun greenScoreIsClamped() {
        val low = RednessScorer.greenScore(
            u = 0,
            v = 255
        )

        val high = RednessScorer.greenScore(
            u = 255,
            v = 0
        )

        assertTrue(low >= -1f)
        assertTrue(low <= 1f)
        assertTrue(high >= -1f)
        assertTrue(high <= 1f)
    }

    @Test
    fun envelopeAttacksAndReleases() {
        val tracker = SignalTracker()

        repeat(10) {
            tracker.update(0f)
        }

        val off = tracker.update(0f)
        val on = tracker.update(0.8f)

        assertFalse(off.isOn)
        assertTrue(on.envelope > off.envelope)

        repeat(20) {
            tracker.update(0f)
        }

        assertFalse(tracker.isOn)
    }

    @Test
    fun greenTrackerStartsOff() {
        val tracker = GreenSignalTracker()

        assertFalse(tracker.isOn)
    }

    @Test
    fun greenTrackerRespondsToGreenSignal() {
        val tracker = GreenSignalTracker()

        repeat(10) {
            tracker.update(0f)
        }

        val off = tracker.update(0f)
        val on = tracker.update(0.8f)

        assertFalse(off.isOn)
        assertTrue(on.envelope > off.envelope)
    }

    @Test
    fun greenTrackerReleasesAfterSignalDisappears() {
        val tracker = GreenSignalTracker()

        repeat(10) {
            tracker.update(0f)
        }

        repeat(10) {
            tracker.update(0.8f)
        }

        repeat(30) {
            tracker.update(0f)
        }

        assertFalse(tracker.isOn)
    }

    @Test
    fun greenTrackerResetClearsState() {
        val tracker = GreenSignalTracker()

        repeat(10) {
            tracker.update(0.8f)
        }

        tracker.reset()

        assertFalse(tracker.isOn)
        assertEquals(0f, tracker.baseline, 0.0001f)
        assertEquals(0f, tracker.envelope, 0.0001f)
        assertEquals(0f, tracker.threshold, 0.0001f)
    }
}