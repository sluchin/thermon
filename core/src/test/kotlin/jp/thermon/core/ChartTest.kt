/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 試験の窓の開始の時刻 (UNIX 時刻 (s)). */
private const val FROM = 1000L

/** 試験の窓の終了の時刻 (UNIX 時刻 (s)). */
private const val TO = 2000L

/** 試験の区切りの長さ (秒). */
private const val BUCKET = 100L

/** 試験の窓 (区切りは 10 個). */
private val WINDOW = ChartWindow(FROM, TO, BUCKET)

/** 浮動小数点の比較の許容差. */
private const val DELTA = 1e-9

/**
 * 試験用の測定値を作る.
 *
 * @param ts 時刻
 * @param temperature 温度
 * @param humidity 湿度
 * @return 測定値
 */
private fun reading(
    ts: Long,
    temperature: Double,
    humidity: Double? = null,
): Reading = Reading("AA:BB:CC:DD:EE:01", ts, null, temperature, humidity, null)

/** [ChartRange] と [buildChartSeries] の単体テスト. */
class ChartTest {
    /** 範囲ごとに, 窓の長さと, 区切りの長さ (点の数は, 120 で同じ) が決まる. */
    @Test
    fun rangeWindow() {
        assertEquals(ChartWindow(6400, 10000, 30), ChartRange.HOUR_1.window(10000))
        assertEquals(ChartWindow(-11600, 10000, 180), ChartRange.HOUR_6.window(10000))
        assertEquals(ChartWindow(10000 - 86400, 10000, 720), ChartRange.HOUR_24.window(10000))
        assertEquals(3600L, ChartRange.HOUR_1.seconds)
    }

    /** 区切りの長さが 1 未満の窓は, 作れない. */
    @Test
    fun windowRejectsNonPositiveBucket() {
        assertFailsWith<IllegalArgumentException> { ChartWindow(0, 100, 0) }
    }

    /** 同じ区切りの測定値は, 平均して, 区切りの中心の時刻の 1 点にする. */
    @Test
    fun buildAveragesWithinBucket() {
        val readings = listOf(reading(1010, 20.0), reading(1090, 22.0), reading(1150, 30.0))

        val series = assertNotNull(buildChartSeries(readings, WINDOW, 1.0) { it.temperatureC })

        assertEquals(1, series.segments.size)
        assertEquals(
            listOf(ChartPoint(1050, 21.0), ChartPoint(1150, 30.0)),
            series.segments[0],
        )
        assertEquals(WINDOW, series.window)
    }

    /** 窓の外の測定値と, 値のない測定値は, 除く (窓の両端は, 含む). */
    @Test
    fun buildSkipsOutOfWindowAndMissing() {
        val readings =
            listOf(
                reading(FROM - 1, 99.0, 99.0),
                reading(FROM, 10.0, 40.0),
                reading(1500, 12.0, null),
                reading(TO, 14.0, 60.0),
                reading(TO + 1, 99.0, 99.0),
            )

        val temperature = assertNotNull(buildChartSeries(readings, WINDOW, 1.0) { it.temperatureC })
        val humidity = assertNotNull(buildChartSeries(readings, WINDOW, 1.0) { it.humidity })

        assertEquals(listOf(10.0, 12.0, 14.0), temperature.segments.flatten().map { it.value })
        assertEquals(listOf(40.0, 60.0), humidity.segments.flatten().map { it.value })
    }

    /** 窓の中に値がなければ, null. */
    @Test
    fun buildReturnsNullWithoutData() {
        assertNull(buildChartSeries(emptyList(), WINDOW, 1.0) { it.temperatureC })
        assertNull(
            buildChartSeries(listOf(reading(FROM - 1, 1.0)), WINDOW, 1.0) { it.temperatureC },
        )
        assertNull(buildChartSeries(listOf(reading(1500, 1.0)), WINDOW, 1.0) { it.humidity })
    }

    /** 測定値のない区切りが 2 つまでなら, 線をつなぐ. 3 つ以上なら, そこで切る. */
    @Test
    fun buildSplitsAtGap() {
        // 区切りの番号: 0, 1 (連続), 4 (2 つ空き: つなぐ), 8 (3 つ空き: 切る), 9 (連続)
        val readings =
            listOf(1050L, 1150L, 1450L, 1850L, 1950L).map { reading(it, it.toDouble()) }

        val series = assertNotNull(buildChartSeries(readings, WINDOW, 1.0) { it.temperatureC })

        assertEquals(2, series.segments.size)
        assertEquals(listOf(1050L, 1150L, 1450L), series.segments[0].map { it.ts })
        assertEquals(listOf(1850L, 1950L), series.segments[1].map { it.ts })
    }

    /** 縦軸は, 値の幅が十分なら, 上下に 5% の余白を足す. */
    @Test
    fun buildAddsMarginToWideSpan() {
        val readings = listOf(reading(1050, 10.0), reading(1150, 30.0))

        val series = assertNotNull(buildChartSeries(readings, WINDOW, 1.0) { it.temperatureC })

        assertEquals(9.0, series.minValue, DELTA)
        assertEquals(31.0, series.maxValue, DELTA)
    }

    /** 縦軸は, 値の幅が最小の幅より小さいときは, 中心を保って, 最小の幅まで広げる. */
    @Test
    fun buildWidensNarrowSpan() {
        val readings = listOf(reading(1050, 20.0), reading(1150, 20.2))

        val series = assertNotNull(buildChartSeries(readings, WINDOW, 1.0) { it.temperatureC })

        assertEquals(19.6, series.minValue, DELTA)
        assertEquals(20.6, series.maxValue, DELTA)
    }

    /** 値が 1 点だけのときも, 最小の幅で, 縦軸を作る. */
    @Test
    fun buildSinglePoint() {
        val series =
            assertNotNull(
                buildChartSeries(listOf(reading(1050, 20.0)), WINDOW, 4.0) { it.temperatureC },
            )

        assertEquals(18.0, series.minValue, DELTA)
        assertEquals(22.0, series.maxValue, DELTA)
    }

    /** 共通の軸は, 縦軸が, 全ての線が入る範囲で, 横軸が, 先頭の線の窓になる. */
    @Test
    fun commonAxisCoversAllSeries() {
        val a = ChartSeries(emptyList(), WINDOW, 18.0, 24.0)
        val b = ChartSeries(emptyList(), WINDOW, 10.0, 20.0)

        assertEquals(ChartAxis(WINDOW, 10.0, 24.0), commonAxis(listOf(a, b)))
        assertEquals(ChartAxis(WINDOW, 18.0, 24.0), commonAxis(listOf(a)))
    }

    /** 線がないときは, 共通の軸も, ない. */
    @Test
    fun commonAxisEmpty() {
        assertNull(commonAxis(emptyList()))
    }
}
