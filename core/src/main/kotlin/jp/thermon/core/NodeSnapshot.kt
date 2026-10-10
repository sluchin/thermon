/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

/** 温度のグラフの縦軸の幅の最小値 (℃). わずかな変化が, 大きく見えないようにする. */
private const val TEMPERATURE_MIN_SPAN = 1.0

/** 湿度のグラフの縦軸の幅の最小値 (%). */
private const val HUMIDITY_MIN_SPAN = 5.0

/**
 * 1 台の機器の, 画面に表示する内容 (最新の測定値と, 温度と湿度のグラフ).
 *
 * @property node 機器の識別子
 * @property latest 最新の測定値 (ないときは, null)
 * @property temperature 温度のグラフ (範囲の中に, 値がないときは, null)
 * @property humidity 湿度のグラフ (範囲の中に, 値がないときは, null)
 */
data class NodeSnapshot(
    val node: String,
    val latest: Reading?,
    val temperature: ChartSeries?,
    val humidity: ChartSeries?,
)

/**
 * 1 台の機器の, 最新の測定値と, 範囲の中の測定値を取り出して, 画面に表示する内容にする.
 *
 * 取り出しに失敗したときは, 例外をそのまま伝える (呼び出し元が, 画面に表示する).
 *
 * @param repository 測定値を取り出す入り口
 * @param node 機器の識別子
 * @param range グラフの範囲
 * @param nowTs 現在の時刻 (UNIX 時刻 (s)). 範囲の終わり
 * @return 画面に表示する内容
 */
suspend fun loadSnapshot(
    repository: ReadingsRepository,
    node: String,
    range: ChartRange,
    nowTs: Long,
): NodeSnapshot {
    val window = range.window(nowTs)
    val readings = repository.range(node, window.fromTs, window.toTs)
    return NodeSnapshot(
        node = node,
        latest = repository.latest(node),
        temperature = buildChartSeries(readings, window, TEMPERATURE_MIN_SPAN) { it.temperatureC },
        humidity = buildChartSeries(readings, window, HUMIDITY_MIN_SPAN) { it.humidity },
    )
}
