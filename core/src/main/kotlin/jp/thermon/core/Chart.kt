/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import java.util.TreeMap

/** 1 本の線の点の数 (範囲の長さを, この数で割った時間ごとに, 測定値を平均する). */
private const val CHART_POINTS = 120L

/** 1 時間の秒数. */
private const val SECONDS_PER_HOUR = 3600L

/** 範囲の長さ (時間): 1 時間. */
private const val RANGE_HOURS_1 = 1L

/** 範囲の長さ (時間): 6 時間. */
private const val RANGE_HOURS_6 = 6L

/** 範囲の長さ (時間): 24 時間. */
private const val RANGE_HOURS_24 = 24L

/** 測定値のない時間が, この数 (区切りの数) を超えたら, 線を切る (機器が止まっていた間をつながない). */
private const val MAX_GAP_BUCKETS = 2L

/** 値の幅が十分なとき, 上下に足す余白 (値の幅に対する割合). */
private const val MARGIN_RATIO = 0.05

/** 1 回で, 2 で割って, 中心を求めるための値. */
private const val HALF = 2

/**
 * グラフで表示する時間の範囲 (現在から, 過去に向かって).
 *
 * @property hours 範囲の長さ (時間)
 */
enum class ChartRange(
    val hours: Long,
) {
    /** 直近の 1 時間. */
    HOUR_1(RANGE_HOURS_1),

    /** 直近の 6 時間. */
    HOUR_6(RANGE_HOURS_6),

    /** 直近の 24 時間. */
    HOUR_24(RANGE_HOURS_24),
    ;

    /** 範囲の長さ (秒). */
    val seconds: Long get() = hours * SECONDS_PER_HOUR

    /** 測定値を平均する区切りの長さ (秒). 範囲が長いほど, 長くなる (線の点の数は, 同じ). */
    val bucketSeconds: Long get() = seconds / CHART_POINTS

    /**
     * 現在の時刻から, 過去に向かう範囲の窓を作る.
     *
     * @param nowTs 現在の時刻 (UNIX 時刻 (s))
     * @return 窓
     */
    fun window(nowTs: Long): ChartWindow = ChartWindow(nowTs - seconds, nowTs, bucketSeconds)
}

/**
 * グラフの時間の窓 (横軸の範囲と, 平均する区切りの長さ).
 *
 * @property fromTs 開始の時刻 (UNIX 時刻 (s). 含む)
 * @property toTs 終了の時刻 (UNIX 時刻 (s). 含む)
 * @property bucketSeconds 測定値を平均する区切りの長さ (秒. 1 以上)
 * @throws IllegalArgumentException 区切りの長さが 1 未満のとき
 */
data class ChartWindow(
    val fromTs: Long,
    val toTs: Long,
    val bucketSeconds: Long,
) {
    init {
        require(bucketSeconds >= 1) { "bucketSeconds must be positive: $bucketSeconds" }
    }
}

/**
 * グラフの 1 点.
 *
 * @property ts 区切りの中心の時刻 (UNIX 時刻 (s))
 * @property value 区切りの中の平均値
 */
data class ChartPoint(
    val ts: Long,
    val value: Double,
)

/**
 * グラフの 1 本の線 (縦軸の範囲を含む).
 *
 * @property segments 線の部分 (測定値のない時間で切れた, 点の並び. 古い順)
 * @property window 横軸の窓
 * @property minValue 縦軸の下限 (余白を含む)
 * @property maxValue 縦軸の上限 (余白を含む)
 */
data class ChartSeries(
    val segments: List<List<ChartPoint>>,
    val window: ChartWindow,
    val minValue: Double,
    val maxValue: Double,
)

/** 区切りの中の, 値の合計と個数 (平均を求める途中の値). */
private class Bucket {
    /** 値の合計. */
    var sum = 0.0

    /** 値の個数. */
    var count = 0
}

/**
 * 測定値から, グラフの 1 本の線を作る.
 *
 * 窓の中の測定値を, 区切りごとに平均して, 点にする. 測定値のない区切りが [MAX_GAP_BUCKETS] を
 * 超えて続いたら, そこで線を切る. 縦軸の範囲は, 値の最小と最大に余白を足したもので, 幅が
 * [minSpan] より小さいときは, [minSpan] まで広げる (わずかな変化が, 大きく見えないように).
 *
 * @param readings 測定値 (順序は, 問わない)
 * @param window 横軸の窓
 * @param minSpan 縦軸の幅の最小値 (線の値と, 同じ単位)
 * @param value 測定値から, グラフにする値を取り出す関数 (ないときは, null. その測定値は, 除く)
 * @return 線. 窓の中に, 値が 1 つもないときは, null
 */
fun buildChartSeries(
    readings: List<Reading>,
    window: ChartWindow,
    minSpan: Double,
    value: (Reading) -> Double?,
): ChartSeries? {
    val buckets = TreeMap<Long, Bucket>()
    for (reading in readings) {
        val v = value(reading)
        if (v == null || reading.ts < window.fromTs || reading.ts > window.toTs) {
            continue
        }
        val bucket =
            buckets.getOrPut(
                (reading.ts - window.fromTs) / window.bucketSeconds,
            ) { Bucket() }
        bucket.sum += v
        bucket.count++
    }
    if (buckets.isEmpty()) {
        return null
    }

    val segments = mutableListOf<MutableList<ChartPoint>>()
    var previous = 0L
    for ((index, bucket) in buckets) {
        // 前の点との間にある, 測定値のない区切りの数が, 上限を超えたら, 線を切る
        if (segments.isEmpty() || index - previous - 1 > MAX_GAP_BUCKETS) {
            segments.add(mutableListOf())
        }
        val center = window.fromTs + index * window.bucketSeconds + window.bucketSeconds / HALF
        segments.last().add(ChartPoint(center, bucket.sum / bucket.count))
        previous = index
    }

    val values = segments.flatten().map { it.value }
    val low = values.min()
    val high = values.max()
    val span = high - low
    val margin = if (span < minSpan) (minSpan - span) / HALF else span * MARGIN_RATIO
    return ChartSeries(segments, window, low - margin, high + margin)
}
