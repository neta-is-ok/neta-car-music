package io.neta.music

import io.neta.music.ui.LyricLine
import io.neta.music.ui.ParsedLyrics
import io.neta.music.ui.components.SettlingOffsetState
import io.neta.music.ui.components.spokenPositionBucket
import io.neta.music.ui.player.ambientSpinEnabled
import io.neta.music.ui.player.displayLines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerPerformanceHelpersTest {
    @Test
    fun spokenPositionBucketsTenSeconds() {
        assertEquals(0L, spokenPositionBucket(9_999L))
        assertEquals(10_000L, spokenPositionBucket(10_000L))
        assertEquals(10_000L, spokenPositionBucket(19_999L))
        assertEquals(120_000L, spokenPositionBucket(125_678L))
    }

    @Test
    fun coverSpinsWheneverPlayingWithMotionEvenWithoutACover() {
        assertTrue(ambientSpinEnabled(playing = true, reduceMotion = false))
        assertFalse(ambientSpinEnabled(playing = false, reduceMotion = false))
        assertFalse(ambientSpinEnabled(playing = true, reduceMotion = true))
        assertFalse(ambientSpinEnabled(playing = false, reduceMotion = true))
    }

    @Test
    fun unsyncedLyricsSplitKeepsOrderAndMarksUnsyncedLines() {
        val parsed = ParsedLyrics(emptyList(), "第一行\n\n第二行\n", false)
        val lines = parsed.displayLines()
        assertEquals(listOf("第一行", "第二行"), lines.map { it.text })
        assertTrue(lines.all { it.timeMs == -1L })
        val synced = ParsedLyrics(listOf(LyricLine(1_000L, "一行")), "[00:01]一行", true)
        assertSame(synced.lines, synced.displayLines())
    }

    @Test
    fun dragAccumulatesWithoutStartingCoroutinesPerMove() = runTest {
        val state = SettlingOffsetState(this, animateToZero = { _, _ -> })
        state.begin()
        state.add(30f)
        state.add(-10f)
        assertEquals(20f, state.offset, 0f)
        assertFalse(state.isSettling)
    }

    @Test
    fun newGestureCancelsPreviousSettleAndKeepsOffset() = runTest {
        val started = CompletableDeferred<Float>()
        val state = SettlingOffsetState(this, animateToZero = { from, _ ->
            started.complete(from)
            awaitCancellation()
        })
        state.begin()
        state.add(80f)
        state.settle()
        runCurrent()
        assertEquals(80f, started.await(), 0f)
        assertTrue(state.isSettling)
        state.begin()
        assertFalse(state.isSettling)
        assertEquals(80f, state.offset, 0f)
    }

    @Test
    fun settleDrivesOffsetBackToZero() = runTest {
        val state = SettlingOffsetState(this, animateToZero = { _, onValue -> onValue(0f) })
        state.begin()
        state.add(-120f)
        state.settle()
        runCurrent()
        assertEquals(0f, state.offset, 0f)
        assertFalse(state.isSettling)
    }
}
