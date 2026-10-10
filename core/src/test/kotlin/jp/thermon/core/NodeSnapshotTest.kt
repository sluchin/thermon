/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 試験の現在の時刻 (UNIX 時刻 (s)). */
private const val NOW = 100000L

/** 試験の機器の識別子. */
private const val NODE = "AA:BB:CC:DD:EE:01"

/**
 * 試験用の測定値を取り出す入り口 (取り出しの引数を記録する).
 *
 * @property readings [range] が返す測定値
 * @property latest [latest] が返す測定値
 * @property failure 取り出しで投げる例外 (null なら, 投げない)
 */
private class FakeRepository(
    private val readings: List<Reading>,
    private val latest: Reading?,
    private val failure: Exception? = null,
) : ReadingsRepository {
    /** [range] に渡された引数 (機器, 開始, 終了). */
    var rangeArgs: Triple<String, Long, Long>? = null

    override suspend fun range(
        node: String,
        fromTs: Long,
        toTs: Long,
    ): List<Reading> {
        failure?.let { throw it }
        rangeArgs = Triple(node, fromTs, toTs)
        return readings
    }

    override suspend fun latest(node: String): Reading? = latest
}

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
    humidity: Double?,
): Reading = Reading(NODE, ts, null, temperature, humidity, null)

/** [loadSnapshot] の単体テスト. */
class NodeSnapshotTest {
    /** 範囲の窓で取り出して, 最新の測定値と, 温度と湿度のグラフを作る. */
    @Test
    fun loadSnapshotBuildsLatestAndCharts() =
        runTest {
            val latest = reading(NOW - 10, 21.0, 50.0)
            val repository =
                FakeRepository(listOf(reading(NOW - 100, 20.0, 40.0), latest), latest)

            val snapshot = loadSnapshot(repository, NODE, ChartRange.HOUR_1, NOW)

            assertEquals(Triple(NODE, NOW - 3600, NOW), repository.rangeArgs)
            assertEquals(NODE, snapshot.node)
            assertEquals(latest, snapshot.latest)
            assertEquals(
                listOf(
                    20.0,
                    21.0,
                ),
                assertNotNull(snapshot.temperature).segments.flatten().map {
                    it.value
                },
            )
            assertEquals(
                listOf(
                    40.0,
                    50.0,
                ),
                assertNotNull(snapshot.humidity).segments.flatten().map {
                    it.value
                },
            )
        }

    /** 湿度を測れない機器 (湿度が null) は, 湿度のグラフを作らない. */
    @Test
    fun loadSnapshotWithoutHumidity() =
        runTest {
            val repository = FakeRepository(listOf(reading(NOW - 100, 20.0, null)), null)

            val snapshot = loadSnapshot(repository, NODE, ChartRange.HOUR_6, NOW)

            assertNull(snapshot.latest)
            assertNotNull(snapshot.temperature)
            assertNull(snapshot.humidity)
        }

    /** 範囲の中に測定値がなければ, どちらのグラフも作らない. */
    @Test
    fun loadSnapshotWithoutData() =
        runTest {
            val snapshot =
                loadSnapshot(FakeRepository(emptyList(), null), NODE, ChartRange.HOUR_24, NOW)

            assertNull(snapshot.temperature)
            assertNull(snapshot.humidity)
        }

    /** 取り出しに失敗したら, 例外をそのまま伝える. */
    @Test
    fun loadSnapshotPropagatesFailure() =
        runTest {
            val repository = FakeRepository(emptyList(), null, IllegalStateException("boom"))

            val error =
                assertFailsWith<IllegalStateException> {
                    loadSnapshot(repository, NODE, ChartRange.HOUR_1, NOW)
                }

            assertEquals("boom", error.message)
        }
}
