/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import java.time.LocalDate
import java.time.ZoneOffset
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
        assertEquals(7L * 86400L, ChartRange.DAY_7.seconds)
        assertEquals(30L * 86400L, ChartRange.DAY_30.seconds)
        assertEquals(365L * 86400L, ChartRange.YEAR_1.seconds)
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

    /** 保持する時間 (holdSeconds) までの穴は, 区切りの数が多くても, 線をつなぐ. それを超えたら切る. */
    @Test
    fun buildHoldsLongerGap() {
        // 区切りの番号: 0, 4 (3 つ空き: 300 s), 9 (4 つ空き: 400 s). 保持 450 s なら全部つなぎ, 350 s なら 2 つ目の穴で切る
        val readings = listOf(1050L, 1450L, 1950L).map { reading(it, it.toDouble()) }

        val held = assertNotNull(buildChartSeries(readings, WINDOW, 1.0, 450L) { it.temperatureC })
        val cut = assertNotNull(buildChartSeries(readings, WINDOW, 1.0, 350L) { it.temperatureC })

        assertEquals(
            listOf(listOf(1050L, 1450L, 1950L)),
            held.segments.map { s -> s.map { it.ts } },
        )
        assertEquals(
            listOf(listOf(1050L, 1450L), listOf(1950L)),
            cut.segments.map { s ->
                s.map { it.ts }
            },
        )
    }

    /** 日付で選んだ窓は, 開始の日の 0 時から, 終了の日の終わりまで. 区切りは, 長さを 120 で割る. */
    @Test
    fun customWindowCoversWholeDays() {
        val zone = ZoneOffset.UTC
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 1, 2)
        val fromTs = from.atStartOfDay(zone).toEpochSecond()

        val window = customWindow(from, to, zone, fromTs + 10L * 86400L)

        assertEquals(
            ChartWindow(fromTs, fromTs + 2L * 86400L - 1L, (2L * 86400L - 1L) / 120L),
            window,
        )
    }

    /** 終了が現在より先なら現在まで. 開始と終了が逆なら入れ替える. 未来の日なら, 幅は最小 (区切り 1 秒). */
    @Test
    fun customWindowClampsAndSwaps() {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 1, 1)
        val second = LocalDate.of(2026, 1, 3)
        val fromTs = first.atStartOfDay(zone).toEpochSecond()

        assertEquals(
            ChartWindow(fromTs, fromTs + 1000L, 8L),
            customWindow(
                second,
                first,
                zone,
                fromTs + 1000L,
            ),
        )
        assertEquals(
            ChartWindow(fromTs, fromTs, 1L),
            customWindow(first, second, zone, fromTs - 5L),
        )
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

    /** 横軸の位置から時刻を求める (範囲の外は, 端にそろえる). */
    @Test
    fun timeAtFractionMapsPosition() {
        assertEquals(FROM, timeAtFraction(WINDOW, 0.0))
        assertEquals(1500L, timeAtFraction(WINDOW, 0.5))
        assertEquals(TO, timeAtFraction(WINDOW, 1.0))
        assertEquals(FROM, timeAtFraction(WINDOW, -0.3))
        assertEquals(TO, timeAtFraction(WINDOW, 1.7))
    }

    /** 最も近い点を返す. 区切り 1 つ分より遠いときと, 点がないときは, null. */
    @Test
    fun nearestPointFindsClosest() {
        val series =
            assertNotNull(
                buildChartSeries(
                    listOf(reading(1050, 20.0), reading(1150, 22.0), reading(1850, 25.0)),
                    WINDOW,
                    4.0,
                ) { it.temperatureC },
            )

        assertEquals(ChartPoint(1050, 20.0), nearestPoint(series, 1000))
        assertEquals(ChartPoint(1150, 22.0), nearestPoint(series, 1140))
        assertEquals(ChartPoint(1850, 25.0), nearestPoint(series, 1900))
        assertNull(nearestPoint(series, 1500))
        assertNull(nearestPoint(ChartSeries(emptyList(), WINDOW, 0.0, 1.0), 1500))
    }
}
