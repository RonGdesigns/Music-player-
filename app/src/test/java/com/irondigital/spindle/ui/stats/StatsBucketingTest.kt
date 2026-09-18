package com.irondigital.spindle.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class StatsBucketingTest {

    private fun todayAt(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun daysAgoAt(days: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -days)
        }.timeInMillis

    // --------------------------------------------------------------- by day

    @Test
    fun `produces exactly one bucket per day in the window`() {
        val buckets = bucketByDay(emptyList(), days = 30)
        assertEquals(30, buckets.size)
        assertTrue(buckets.all { it.plays == 0 })
    }

    @Test
    fun `bucket starts ascend and are one day apart`() {
        val buckets = bucketByDay(emptyList(), days = 7)
        val gaps = buckets.zipWithNext { a, b -> b.dayStartMs - a.dayStartMs }

        assertTrue(gaps.all { it > 0 })
        // A daylight-saving change makes one day 23 or 25 hours long, which is
        // exactly why these are real calendar days rather than 86,400,000 apart.
        assertTrue(gaps.all { it in TimeUnit.HOURS.toMillis(23)..TimeUnit.HOURS.toMillis(25) })
    }

    @Test
    fun `today's plays land in the last bucket`() {
        val buckets = bucketByDay(listOf(todayAt(9), todayAt(14), todayAt(21)), days = 30)
        assertEquals(3, buckets.last().plays)
        assertEquals(3, buckets.sumOf { it.plays })
    }

    @Test
    fun `an event three days ago lands three buckets from the end`() {
        val buckets = bucketByDay(listOf(daysAgoAt(3, 12)), days = 30)
        assertEquals(1, buckets[buckets.size - 4].plays)
        assertEquals(1, buckets.sumOf { it.plays })
    }

    @Test
    fun `events outside the window are dropped rather than clamped`() {
        // Clamping would pile a year of history onto the first visible day and
        // make the chart a single spike.
        val buckets = bucketByDay(listOf(daysAgoAt(400, 12), todayAt(10)), days = 30)
        assertEquals(1, buckets.sumOf { it.plays })
        assertEquals(1, buckets.last().plays)
        assertEquals(0, buckets.first().plays)
    }

    @Test
    fun `a future timestamp does not corrupt the buckets`() {
        val future = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5)
        val buckets = bucketByDay(listOf(future), days = 30)
        assertEquals(0, buckets.sumOf { it.plays })
    }

    @Test
    fun `an event just before midnight belongs to that day, not the next`() {
        val buckets = bucketByDay(listOf(todayAt(23, 59)), days = 30)
        assertEquals(1, buckets.last().plays)
    }

    // -------------------------------------------------------------- by hour

    @Test
    fun `hour buckets always cover the full day`() {
        assertEquals(24, bucketByHour(emptyList()).size)
        assertTrue(bucketByHour(emptyList()).all { it == 0 })
    }

    @Test
    fun `events land in their local hour`() {
        val counts = bucketByHour(listOf(todayAt(9), todayAt(9, 30), todayAt(22)))
        assertEquals(2, counts[9])
        assertEquals(1, counts[22])
        assertEquals(3, counts.sum())
    }

    // -------------------------------------------------------------- streaks

    private fun days(vararg plays: Int) =
        plays.mapIndexed { i, p -> DayBucket(dayStartMs = i.toLong(), plays = p) }

    @Test
    fun `no plays means no streak`() {
        assertEquals(0 to 0, streaks(days(0, 0, 0)))
        assertEquals(0 to 0, streaks(emptyList()))
    }

    @Test
    fun `a run ending today counts as the current streak`() {
        assertEquals(3 to 3, streaks(days(0, 0, 1, 1, 1)))
    }

    @Test
    fun `an empty today does not break a streak that ran up to yesterday`() {
        // It would be unreasonable for a streak to look broken at nine in the
        // morning purely because nothing has been played yet.
        assertEquals(3 to 3, streaks(days(0, 1, 1, 1, 0)))
    }

    @Test
    fun `two empty days do break it`() {
        assertEquals(0 to 3, streaks(days(1, 1, 1, 0, 0)))
    }

    @Test
    fun `the longest streak is reported even when it is not the current one`() {
        val (current, longest) = streaks(days(1, 1, 1, 1, 0, 0, 1, 1))
        assertEquals(2, current)
        assertEquals(4, longest)
    }

    @Test
    fun `a single day of listening is a streak of one`() {
        assertEquals(1 to 1, streaks(days(0, 0, 1)))
    }
}
