/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

/** DynamoDB が測定値を残す期間 (s): 30 日. これより古い測定値は, もう取り出せない. */
private const val RETENTION_SECONDS = 30L * 24L * 3600L

/** 値が変わらなくても, 測定値を 1 点は保存する間隔 (s): 1 時間. */
const val HEARTBEAT_SECONDS = 3600L

/** 端末に保存した測定値を出し入れする入り口. 端末の保存先 (SQLite) に限らず, テストでは偽物に置き換える. */
interface ReadingStore {
    /**
     * 測定値を保存する (機器と時刻が同じ測定値は, すでにあるものを残す).
     *
     * @param readings 保存する測定値
     */
    fun append(readings: List<Reading>)

    /**
     * 機器 [node] の, [fromTs] から [toTs] まで (UNIX 時刻 (s). 両端を含む) の保存した測定値を, 古い順に取り出す.
     *
     * @param node 機器の識別子
     * @param fromTs 開始の時刻
     * @param toTs 終了の時刻
     * @return 測定値 (古い順)
     */
    fun range(
        node: String,
        fromTs: Long,
        toTs: Long,
    ): List<Reading>

    /**
     * 機器 [node] の, 最後に保存した測定値を取り出す.
     *
     * @param node 機器の識別子
     * @return 最後に保存した測定値. 1 件もないときは, null
     */
    fun latest(node: String): Reading?
}

/**
 * 同期の結果.
 *
 * @property stored 新しく保存した測定値の数
 * @property latest AWS から取り出した測定値のうち, 最新のもの (新しい測定値がなかったときは, null)
 */
data class SyncResult(
    val stored: Int,
    val latest: Reading?,
)

/**
 * 古い順の測定値から, 保存するものを選ぶ.
 *
 * 前に保存した測定値から温度か湿度か気圧が変わったものと, 値が変わらなくても [HEARTBEAT_SECONDS] 以上
 * たったものを選ぶ (変わらない間も, 1 時間に 1 点は残る. 機器が止まっていた間と, 区別できる).
 *
 * @param readings 古い順の測定値
 * @param last 最後に保存した測定値 (1 件もないときは, null)
 * @return 保存する測定値 (古い順)
 */
fun selectToStore(
    readings: List<Reading>,
    last: Reading?,
): List<Reading> {
    val selected = mutableListOf<Reading>()
    var previous = last
    for (reading in readings) {
        if (previous == null || isWorthStoring(previous, reading)) {
            selected.add(reading)
            previous = reading
        }
    }
    return selected
}

/**
 * 測定値を保存するかを調べる.
 *
 * @param previous 最後に保存した測定値
 * @param reading 新しい測定値
 * @return 温度か湿度か気圧が変わった, または, [HEARTBEAT_SECONDS] 以上たったなら, true
 */
private fun isWorthStoring(
    previous: Reading,
    reading: Reading,
): Boolean =
    previous.temperatureC != reading.temperatureC ||
        previous.humidity != reading.humidity ||
        previous.pressureHpa != reading.pressureHpa ||
        reading.ts - previous.ts >= HEARTBEAT_SECONDS

/**
 * AWS の新しい測定値を取り出して, 変化があったものを端末に保存する.
 *
 * 最後に保存した時刻の次の秒から取り出す (1 件もなければ, DynamoDB が残す 30 日前から). 1 時間に 1 点は
 * 保存するので, 取り出す範囲は, 通常 1 時間ほどに収まる. 取り出しに失敗したときは, 例外をそのまま伝える.
 *
 * @param remote AWS の測定値を取り出す入り口
 * @param store 端末の保存先
 * @param node 機器の識別子
 * @param nowTs 現在の時刻 (UNIX 時刻 (s))
 * @return 保存した数と, AWS の最新の測定値
 */
suspend fun syncReadings(
    remote: ReadingsRepository,
    store: ReadingStore,
    node: String,
    nowTs: Long,
): SyncResult {
    val last = store.latest(node)
    val fromTs = maxOf((last?.ts ?: 0L) + 1L, nowTs - RETENTION_SECONDS)
    val fetched = remote.range(node, fromTs, Long.MAX_VALUE).sortedBy { it.ts }
    val selected = selectToStore(fetched, last)
    store.append(selected)
    return SyncResult(selected.size, fetched.lastOrNull())
}
