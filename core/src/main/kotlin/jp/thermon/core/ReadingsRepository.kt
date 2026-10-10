/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

/** 測定値を取り出す入り口. DynamoDB に限らず, テストでは偽物に置き換える. */
interface ReadingsRepository {
    /**
     * 機器 [node] の, [fromTs] から [toTs] まで (UNIX 時刻 (s). 両端を含む) の測定値を, 古い順に取り出す.
     *
     * @param node 機器の識別子
     * @param fromTs 開始の時刻
     * @param toTs 終了の時刻
     * @return 測定値 (古い順). 読めない項目は, 除く
     */
    suspend fun range(
        node: String,
        fromTs: Long,
        toTs: Long,
    ): List<Reading>

    /**
     * 機器 [node] の, 最新の測定値を取り出す.
     *
     * @param node 機器の識別子
     * @return 最新の測定値. ないとき (読めない項目を含む) は, null
     */
    suspend fun latest(node: String): Reading?
}
