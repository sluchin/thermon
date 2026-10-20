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
import kotlin.test.assertNull

/** 試験の機器の識別子. */
private const val NODE = "AA:BB:CC:DD:EE:01"

/** 試験の現在の時刻 (UNIX 時刻 (s)): 31 日と少し. */
private const val NOW = 3_000_000L

/** 30 日の秒数. */
private const val DAYS_30 = 30L * 24L * 3600L

/**
 * 試験用の端末の保存先 (メモリ上).
 *
 * @property items 保存した測定値
 */
class FakeStore(
    val items: MutableList<Reading> = mutableListOf(),
) : ReadingStore {
    override fun append(readings: List<Reading>) {
        items.addAll(readings)
    }

    override fun range(
        node: String,
        fromTs: Long,
        toTs: Long,
    ): List<Reading> = items.filter { it.node == node && it.ts in fromTs..toTs }.sortedBy { it.ts }

    override fun latest(node: String): Reading? =
        items.filter { it.node == node }.maxByOrNull { it.ts }
}

/**
 * 試験用の AWS の入り口 (取り出しの引数を記録する).
 *
 * @property readings [range] が返す測定値
 * @property failure 取り出しで投げる例外 (null なら, 投げない)
 */
class FakeRemote(
    private val readings: List<Reading>,
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

    override suspend fun latest(node: String): Reading? = readings.lastOrNull()
}

/**
 * 試験用の測定値を作る.
 *
 * @param ts 時刻
 * @param temperature 温度
 * @param humidity 湿度
 * @param pressure 気圧
 * @return 測定値
 */
fun sample(
    ts: Long,
    temperature: Double,
    humidity: Double? = 50.0,
    pressure: Double? = null,
): Reading = Reading(NODE, ts, null, temperature, humidity, null, pressure)

/** [selectToStore] と [syncReadings] の単体テスト. */
class ReadingStoreTest {
    /** 保存したものがなければ, 最初の測定値は保存する. 変わらない測定値は, 保存しない. */
    @Test
    fun selectKeepsFirstAndDropsUnchanged() {
        val readings = listOf(sample(0, 20.0), sample(60, 20.0), sample(120, 20.0))

        assertEquals(listOf(readings[0]), selectToStore(readings, null))
    }

    /** 温度か湿度が変わったら, 保存する (湿度が, ある, ないの変化も). */
    @Test
    fun selectKeepsChanges() {
        val readings =
            listOf(
                sample(0, 20.0),
                sample(60, 20.1),
                sample(120, 20.1, 51.0),
                sample(180, 20.1, null),
            )

        assertEquals(readings, selectToStore(readings, null))
    }

    /** 気圧だけが変わっても, 保存する (気圧が, ある, ないの変化も). */
    @Test
    fun selectKeepsPressureChanges() {
        val readings =
            listOf(
                sample(0, 20.0, pressure = 1013.2),
                sample(60, 20.0, pressure = 1013.2),
                sample(120, 20.0, pressure = 1012.9),
                sample(180, 20.0),
            )

        assertEquals(listOf(readings[0], readings[2], readings[3]), selectToStore(readings, null))
    }

    /** 保存したものと同じ値なら, 保存しない. 1 時間 (ちょうど) たったら, 同じ値でも保存する. */
    @Test
    fun selectKeepsHeartbeat() {
        val last = sample(0, 20.0)
        val readings =
            listOf(sample(3599, 20.0), sample(3600, 20.0), sample(7199, 20.0), sample(7200, 20.0))

        assertEquals(listOf(readings[1], readings[3]), selectToStore(readings, last))
    }

    /** 保存するものがないときは, 空. */
    @Test
    fun selectEmpty() {
        assertEquals(emptyList(), selectToStore(emptyList(), sample(0, 20.0)))
    }

    /** 保存したものがなければ, 30 日前から取り出して, 変化があったものを, 古い順に保存する. */
    @Test
    fun syncWithEmptyStoreFetchesRetentionPeriod() =
        runTest {
            val remote = FakeRemote(listOf(sample(NOW - 10, 21.0), sample(NOW - 100, 20.0)))
            val store = FakeStore()

            val result = syncReadings(remote, store, NODE, NOW)

            assertEquals(Triple(NODE, NOW - DAYS_30, Long.MAX_VALUE), remote.rangeArgs)
            assertEquals(listOf(NOW - 100, NOW - 10), store.items.map { it.ts })
            assertEquals(2, result.stored)
            assertEquals(NOW - 10, result.latest?.ts)
        }

    /** 保存したものがあれば, その次の秒から取り出す. 変わらない測定値は, 保存しないが, 最新として返す. */
    @Test
    fun syncFetchesAfterLastStored() =
        runTest {
            val last = sample(NOW - 500, 20.0)
            val remote = FakeRemote(listOf(sample(NOW - 100, 20.0)))
            val store = FakeStore(mutableListOf(last))

            val result = syncReadings(remote, store, NODE, NOW)

            assertEquals(Triple(NODE, NOW - 499, Long.MAX_VALUE), remote.rangeArgs)
            assertEquals(listOf(last), store.items)
            assertEquals(0, result.stored)
            assertEquals(NOW - 100, result.latest?.ts)
        }

    /** 保存したものが 30 日より古ければ, 30 日前から取り出す (消えたものは, 取り出せない). */
    @Test
    fun syncLimitsToRetentionPeriod() =
        runTest {
            val remote = FakeRemote(emptyList())
            val store = FakeStore(mutableListOf(sample(100, 20.0)))

            val result = syncReadings(remote, store, NODE, NOW)

            assertEquals(Triple(NODE, NOW - DAYS_30, Long.MAX_VALUE), remote.rangeArgs)
            assertEquals(0, result.stored)
            assertNull(result.latest)
        }

    /** 取り出しに失敗したら, 例外をそのまま伝える (何も保存しない). */
    @Test
    fun syncPropagatesFailure() =
        runTest {
            val store = FakeStore()

            val error =
                assertFailsWith<IllegalStateException> {
                    syncReadings(
                        FakeRemote(emptyList(), IllegalStateException("boom")),
                        store,
                        NODE,
                        NOW,
                    )
                }

            assertEquals("boom", error.message)
            assertEquals(emptyList(), store.items)
        }
}
