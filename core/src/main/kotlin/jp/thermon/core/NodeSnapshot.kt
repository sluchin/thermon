/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlin.coroutines.cancellation.CancellationException

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
 * @property syncError AWS から取り出せなかったときの, エラーのメッセージ (端末に保存した測定値を表示する. 取り出せたときは, null)
 */
data class NodeSnapshot(
    val node: String,
    val latest: Reading?,
    val temperature: ChartSeries?,
    val humidity: ChartSeries?,
    val syncError: String?,
)

/**
 * 1 台の機器の, AWS の新しい測定値を端末に保存して, 窓の中の保存した測定値から, 画面に表示する内容にする.
 *
 * AWS から取り出せなかったときは, 端末に保存した測定値だけで表示する (エラーのメッセージを [NodeSnapshot.syncError]
 * に入れる). 保存した測定値が 1 件もないときは, 取り出しの失敗を, そのまま伝える (呼び出し元が, 画面に表示する).
 *
 * @param remote AWS の測定値を取り出す入り口
 * @param store 端末の保存先
 * @param node 機器の識別子
 * @param window グラフの窓
 * @param nowTs 現在の時刻 (UNIX 時刻 (s))
 * @return 画面に表示する内容
 */
suspend fun loadSnapshot(
    remote: ReadingsRepository,
    store: ReadingStore,
    node: String,
    window: ChartWindow,
    nowTs: Long,
): NodeSnapshot {
    val sync = runCatching { syncReadings(remote, store, node, nowTs) }
    val failure = sync.exceptionOrNull()
    // 止められたとき (画面の範囲を選び直したときなど) は, 失敗として扱わず, そのまま伝える
    if (failure is CancellationException) {
        throw failure
    }
    val latest = sync.getOrNull()?.latest ?: store.latest(node)
    if (failure != null && latest == null) {
        throw failure
    }
    val readings = store.range(node, window.fromTs, window.toTs)
    return NodeSnapshot(
        node = node,
        latest = latest,
        temperature =
            buildChartSeries(
                readings,
                window,
                TEMPERATURE_MIN_SPAN,
                HEARTBEAT_SECONDS * 2L,
            ) { it.temperatureC },
        humidity =
            buildChartSeries(
                readings,
                window,
                HUMIDITY_MIN_SPAN,
                HEARTBEAT_SECONDS * 2L,
            ) {
                it.humidity
            },
        syncError = failure?.let { it.message ?: it.toString() },
    )
}
