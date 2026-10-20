/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 試験の現在の時刻 (UNIX 時刻 (s)). */
private const val NOW = 100000L

/** 試験の機器の識別子. */
private const val NODE = "AA:BB:CC:DD:EE:01"

/** 試験の窓 (直近の 1 時間). */
private val WINDOW = ChartRange.HOUR_1.window(NOW)

/** [loadSnapshot] の単体テスト. */
class NodeSnapshotTest {
    /** AWS の新しい測定値を保存して, 最新の測定値と, 保存した測定値の温度と湿度のグラフを作る. */
    @Test
    fun loadSnapshotSyncsAndBuildsCharts() =
        runTest {
            val latest = sample(NOW - 10, 21.0, 50.0)
            val remote = FakeRemote(listOf(sample(NOW - 100, 20.0, 40.0), latest))
            val store = FakeStore()

            val snapshot = loadSnapshot(remote, store, NODE, WINDOW, NOW)

            assertEquals(2, store.items.size)
            assertEquals(NODE, snapshot.node)
            assertEquals(latest, snapshot.latest)
            assertNull(snapshot.syncError)
            assertEquals(
                listOf(20.0, 21.0),
                assertNotNull(snapshot.temperature).segments.flatten().map { it.value },
            )
            assertEquals(
                listOf(40.0, 50.0),
                assertNotNull(snapshot.humidity).segments.flatten().map { it.value },
            )
        }

    /** 窓の外の保存した測定値は, グラフに入れない. 窓の中が空なら, どちらのグラフも作らない. */
    @Test
    fun loadSnapshotOnlyUsesWindow() =
        runTest {
            val store = FakeStore(mutableListOf(sample(NOW - 7200, 20.0)))

            val snapshot = loadSnapshot(FakeRemote(emptyList()), store, NODE, WINDOW, NOW)

            assertEquals(NOW - 7200, snapshot.latest?.ts)
            assertNull(snapshot.temperature)
            assertNull(snapshot.humidity)
        }

    /** 気圧があれば, 気圧のグラフを作る. 気圧のない機器 (SwitchBot など) は, 作らない. */
    @Test
    fun loadSnapshotBuildsPressureChart() =
        runTest {
            val remote =
                FakeRemote(
                    listOf(
                        sample(NOW - 100, 20.0, pressure = 1013.0),
                        sample(NOW - 10, 20.0, pressure = 1012.0),
                    ),
                )

            val snapshot = loadSnapshot(remote, FakeStore(), NODE, WINDOW, NOW)

            val pressure = assertNotNull(snapshot.pressure)
            assertEquals(listOf(1013.0, 1012.0), pressure.segments.flatten().map { it.value })
            // 縦軸は, 変化 (1 hPa) より広い, 最小の幅 (4 hPa) にする
            assertEquals(4.0, pressure.maxValue - pressure.minValue)
            assertNull(
                loadSnapshot(
                    FakeRemote(listOf(sample(NOW - 10, 20.0))),
                    FakeStore(),
                    NODE,
                    WINDOW,
                    NOW,
                ).pressure,
            )
        }

    /** 湿度を測れない機器 (湿度が null) は, 湿度のグラフを作らない. */
    @Test
    fun loadSnapshotWithoutHumidity() =
        runTest {
            val remote = FakeRemote(listOf(sample(NOW - 100, 20.0, null)))

            val snapshot = loadSnapshot(remote, FakeStore(), NODE, WINDOW, NOW)

            assertNotNull(snapshot.temperature)
            assertNull(snapshot.humidity)
        }

    /** 保存の間隔 (1 時間) より短い 2 時間までの穴は, 線をつなぐ. */
    @Test
    fun loadSnapshotConnectsHourlyPoints() =
        runTest {
            val window = ChartRange.HOUR_24.window(NOW)
            val remote = FakeRemote(emptyList())
            val store = FakeStore(mutableListOf(sample(NOW - 7200, 20.0), sample(NOW - 3600, 21.0)))

            val snapshot = loadSnapshot(remote, store, NODE, window, NOW)

            assertEquals(1, assertNotNull(snapshot.temperature).segments.size)
        }

    /**
     * 値が変わらない間 (窓の中に保存した点が 1 つだけ) も, 窓の前に保存した値から, 窓の左端と, 最新の
     * 測定値まで, 線を引く (室外の温度が, 1 時間, 変わらないとき).
     */
    @Test
    fun loadSnapshotHoldsValueToBothEnds() =
        runTest {
            val stored = sample(NOW - 400, 13.8, 45.0)
            // 保存した最後の測定値のあとに, 同じ値の測定値が届いている (変わらないので, 保存しない)
            val remote = FakeRemote(listOf(sample(NOW - 10, 13.8, 45.0)))
            val store = FakeStore(mutableListOf(sample(NOW - 4000, 13.8, 46.0), stored))

            val snapshot = loadSnapshot(remote, store, NODE, WINDOW, NOW)

            val points = assertNotNull(snapshot.humidity).segments
            assertEquals(1, points.size)
            assertEquals(listOf(46.0, 45.0, 45.0), points.flatten().map { it.value })
            assertEquals(3, assertNotNull(snapshot.temperature).segments.flatten().size)
        }

    /** 窓の前の値は, 次の測定値まで, 線がつながるときだけ, 窓の左端に置く. */
    @Test
    fun withHeldEndsAddsHeadOnlyWhenConnected() {
        val window = ChartWindow(1000, 5000, 60)
        val before = sample(900, 20.0)
        val inside = listOf(sample(2000, 21.0))

        assertEquals(
            listOf(1000L, 2000L),
            withHeldEnds(inside, before, null, window, 1500).map { it.ts },
        )
        assertEquals(20.0, withHeldEnds(inside, before, null, window, 1500).first().temperatureC)
        // 次の測定値まで, 長すぎる (機器が止まっていた)
        assertEquals(listOf(2000L), withHeldEnds(inside, before, null, window, 1000).map { it.ts })
        // 窓の前の値がない
        assertEquals(listOf(2000L), withHeldEnds(inside, null, null, window, 1500).map { it.ts })
        // 次の測定値が, 1 つもない
        assertEquals(emptyList(), withHeldEnds(emptyList(), before, null, window, 1500))
    }

    /** 最新の測定値は, 窓の中で, 保存した最後の測定値より新しいときだけ, 足す. */
    @Test
    fun withHeldEndsAddsTailOnlyWhenNewerInWindow() {
        val window = ChartWindow(1000, 5000, 60)
        val inside = listOf(sample(2000, 21.0))

        assertEquals(
            listOf(2000L, 4000L),
            withHeldEnds(inside, null, sample(4000, 21.0), window, 1500).map { it.ts },
        )
        // 保存した最後の測定値と同じ (すでに, 線にある)
        assertEquals(
            listOf(2000L),
            withHeldEnds(inside, null, sample(2000, 21.0), window, 1500).map { it.ts },
        )
        // 窓の後ろ (日付で選んだ, 過去の窓) と, 窓の前
        assertEquals(
            listOf(2000L),
            withHeldEnds(inside, null, sample(6000, 21.0), window, 1500).map { it.ts },
        )
        assertEquals(
            emptyList(),
            withHeldEnds(emptyList(), null, sample(500, 21.0), window, 1500),
        )
        // 窓の中に保存した点がなくても, 窓の前の値と, 最新の測定値で, 線を引く
        assertEquals(
            listOf(1000L, 1800L),
            withHeldEnds(emptyList(), sample(900, 20.0), sample(1800, 20.0), window, 1500)
                .map { it.ts },
        )
        // 窓の前の値と, 最新の測定値が同じ (新しい測定値はない)
        assertEquals(
            emptyList(),
            withHeldEnds(emptyList(), sample(900, 20.0), sample(900, 20.0), window, 1500),
        )
    }

    /** AWS から取り出せなくても, 保存した測定値があれば, それを表示して, エラーのメッセージを付ける. */
    @Test
    fun loadSnapshotFallsBackToStore() =
        runTest {
            val remote = FakeRemote(emptyList(), IllegalStateException("offline"))
            val store = FakeStore(mutableListOf(sample(NOW - 100, 20.0)))

            val snapshot = loadSnapshot(remote, store, NODE, WINDOW, NOW)

            assertEquals("offline", snapshot.syncError)
            assertEquals(NOW - 100, snapshot.latest?.ts)
            assertNotNull(snapshot.temperature)
        }

    /** メッセージのない例外は, 例外の文字列をエラーのメッセージにする. */
    @Test
    fun loadSnapshotErrorWithoutMessage() =
        runTest {
            val remote = FakeRemote(emptyList(), IllegalStateException())
            val store = FakeStore(mutableListOf(sample(NOW - 100, 20.0)))

            val snapshot = loadSnapshot(remote, store, NODE, WINDOW, NOW)

            assertEquals("java.lang.IllegalStateException", snapshot.syncError)
        }

    /** AWS から取り出せず, 保存した測定値もなければ, 失敗をそのまま伝える. */
    @Test
    fun loadSnapshotPropagatesFailureWithoutStore() =
        runTest {
            val remote = FakeRemote(emptyList(), IllegalStateException("boom"))

            val error =
                assertFailsWith<IllegalStateException> {
                    loadSnapshot(remote, FakeStore(), NODE, WINDOW, NOW)
                }

            assertEquals("boom", error.message)
        }

    /** 止められた (キャンセルされた) ときは, 保存した測定値があっても, そのまま伝える. */
    @Test
    fun loadSnapshotPropagatesCancellation() =
        runTest {
            val remote = FakeRemote(emptyList(), CancellationException("cancelled"))
            val store = FakeStore(mutableListOf(sample(NOW - 100, 20.0)))

            assertFailsWith<CancellationException> {
                loadSnapshot(remote, store, NODE, WINDOW, NOW)
            }
        }
}
