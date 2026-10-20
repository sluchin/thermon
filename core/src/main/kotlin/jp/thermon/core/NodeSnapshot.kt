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

/** 気圧のグラフの縦軸の幅の最小値 (hPa). 1 日の変化 (数 hPa) が, 見える幅にする. */
private const val PRESSURE_MIN_SPAN = 4.0

/** 測定値のない時間が, この長さ (s) までなら, 線をつなぐ (保存の間隔 (1 時間) の 2 倍). */
private const val HOLD_SECONDS = HEARTBEAT_SECONDS * 2L

/**
 * 1 台の機器の, 画面に表示する内容 (最新の測定値と, 温度と湿度と気圧のグラフ).
 *
 * @property node 機器の識別子
 * @property latest 最新の測定値 (ないときは, null)
 * @property temperature 温度のグラフ (範囲の中に, 値がないときは, null)
 * @property humidity 湿度のグラフ (範囲の中に, 値がないときは, null)
 * @property pressure 気圧のグラフ (範囲の中に, 値がないとき (気圧センサのない機器など) は, null)
 * @property syncError AWS から取り出せなかったときの, エラーのメッセージ (端末に保存した測定値を表示する. 取り出せたときは, null)
 */
data class NodeSnapshot(
    val node: String,
    val latest: Reading?,
    val temperature: ChartSeries?,
    val humidity: ChartSeries?,
    val pressure: ChartSeries?,
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
    val readings =
        withHeldEnds(
            store.range(node, window.fromTs, window.toTs),
            store.range(node, window.fromTs - HOLD_SECONDS, window.fromTs - 1L).lastOrNull(),
            latest,
            window,
            HOLD_SECONDS,
        )
    return NodeSnapshot(
        node = node,
        latest = latest,
        temperature =
            buildChartSeries(
                readings,
                window,
                TEMPERATURE_MIN_SPAN,
                HOLD_SECONDS,
            ) { it.temperatureC },
        humidity =
            buildChartSeries(
                readings,
                window,
                HUMIDITY_MIN_SPAN,
                HOLD_SECONDS,
            ) {
                it.humidity
            },
        pressure =
            buildChartSeries(
                readings,
                window,
                PRESSURE_MIN_SPAN,
                HOLD_SECONDS,
            ) { it.pressureHpa },
        syncError = failure?.let { it.message ?: it.toString() },
    )
}

/**
 * 窓の中の保存した測定値に, 窓の左端と, 最新の測定値を足す (線を, 窓の端と, 今まで届かせる).
 *
 * 端末には, 値が変わったときと, 1 時間ごとにしか保存しない. そのため, 値が変わらない間は, 窓の中に点が
 * ほとんどなく, 線が窓の左端にも, 最新の時刻にも届かない.
 *
 * - 左端: 窓の前に保存した最後の測定値 [before] から, 次の測定値までが [holdSeconds] 以内 (線が, つながる)
 *   なら, その値を, 窓の左端の時刻に置く.
 * - 右端: 最新の測定値 [latest] が, 窓の中にあり, 保存した最後の測定値より新しいなら, 足す (保存しなかった
 *   測定値は, 前に保存した値と同じなので, その時刻まで, 同じ値が続いている).
 *
 * @param readings 窓の中の保存した測定値 (古い順)
 * @param before 窓の前に保存した, 最後の測定値 (ないときは, null)
 * @param latest 最新の測定値 (ないときは, null)
 * @param window 横軸の窓
 * @param holdSeconds 測定値のない時間が, この長さ (s) までなら, 線をつなぐ
 * @return 端を足した測定値 (古い順)
 */
fun withHeldEnds(
    readings: List<Reading>,
    before: Reading?,
    latest: Reading?,
    window: ChartWindow,
    holdSeconds: Long,
): List<Reading> {
    val lastTs = readings.lastOrNull()?.ts ?: before?.ts ?: Long.MIN_VALUE
    val tail = latest?.takeIf { it.ts > lastTs && it.ts in window.fromTs..window.toTs }
    val next = readings.firstOrNull() ?: tail
    val head =
        before
            ?.takeIf { next != null && next.ts - it.ts <= holdSeconds }
            ?.copy(ts = window.fromTs)
    return listOfNotNull(head) + readings + listOfNotNull(tail)
}
