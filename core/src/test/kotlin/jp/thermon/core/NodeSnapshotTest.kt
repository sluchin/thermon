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
