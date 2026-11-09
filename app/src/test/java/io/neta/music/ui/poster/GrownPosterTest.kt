package io.neta.music.ui.poster

import io.neta.music.ui.components.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrownPosterTest {
    @Test
    fun coverTreatmentFollowsTheThreeStates() {
        assertEquals(CoverTreatment(0f), coverTreatment(current = false, hasLyrics = true))
        val now = coverTreatment(current = true, hasLyrics = true)
        assertEquals(0.76f, now.dim, 0.0001f)
        val plain = coverTreatment(current = true, hasLyrics = false)
        assertEquals(0.341f, plain.dim, 0.0001f)
    }

    @Test
    fun numbersArePaddedToTwoDigits() {
        assertEquals("03", posterNumber(3))
        assertEquals("21", posterNumber(21))
        assertEquals("128", posterNumber(128))
    }

    @Test
    fun eqBarsBounceBetweenThreeAndTenDp() {
        var t = 0f
        while (t < 5f) {
            for (lead in floatArrayOf(0f, 0.3f, 0.55f)) {
                val h = eqBarHeight(t, lead)
                assertTrue(h in 3f..10f)
            }
            t += 0.013f
        }
        assertEquals(3f, eqBarHeight(0f, 0f), 0.0001f)
        assertEquals(10f, eqBarHeight(0.8f, 0f), 0.0001f)
        // 1.6 秒一个来回
        assertEquals(eqBarHeight(0.37f, 0.3f), eqBarHeight(1.97f, 0.3f), 0.0001f)
        // 三根错开相位，静止时也是错落的
        assertNotEquals(eqBarHeight(0f, 0f), eqBarHeight(0f, 0.3f), 0.1f)
        assertNotEquals(eqBarHeight(0f, 0.3f), eqBarHeight(0f, 0.55f), 0.1f)
    }

    @Test
    fun cookieTurnsOnceEveryFourteenSeconds() {
        assertEquals(180f, advanceCookieSpin(0f, 7_000_000_000L), 0.01f)
        assertEquals(10f, advanceCookieSpin(350f, 14_000_000_000L / 18), 0.01f)
        assertTrue(advanceCookieSpin(359f, 1_000_000_000L) in 0f..360f)
    }

    @Test
    fun cardSizesMatchTheWall() {
        // 大卡就是墙上的 6×6：手机 332、平板 606、电视 451
        assertEquals(WallMetrics.Phone.grownSize, GrownPosterStyle.of(DeviceKind.Phone).card.value, 0.001f)
        assertEquals(WallMetrics.Tablet.grownSize, GrownPosterStyle.of(DeviceKind.Tablet).card.value, 0.001f)
        assertEquals(WallMetrics.Tv.grownSize, GrownPosterStyle.of(DeviceKind.Tv).card.value, 0.001f)
    }

    @Test
    fun bigTitlesFollowThePrototype() {
        assertEquals(34f, GrownPosterStyle.Phone.bigTitleSp, 0f)
        assertEquals(60f, GrownPosterStyle.Tablet.bigTitleSp, 0f)
        assertEquals(46f, GrownPosterStyle.Tv.bigTitleSp, 0f)
        assertEquals(34f, GrownPosterStyle.Phone.plainTitleSp, 0.001f)
    }
}
