/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [findOutdoor] と [detectAlerts] と [newAlerts] の単体テスト. */
class OutdoorAlertTest {
    private val now = 100_000L

    private fun reading(
        temperatureC: Double,
        humidity: Double?,
        ts: Long = now,
    ) = Reading("AA", ts, "switchbot", temperatureC, humidity, null)

    /** 表示名が「室外」の機器を探す. */
    @Test
    fun findOutdoorByLabel() {
        val nodes = parseNodes("AA=室内,BB=室外")

        assertEquals(NodeSpec("BB", "室外"), findOutdoor(nodes))
    }

    /** 「室外」がないときは, null. */
    @Test
    fun findOutdoorWithoutLabel() {
        assertNull(findOutdoor(parseNodes("AA=室内,BB")))
    }

    /** 温度が 0 ℃ 未満なら凍る, ちょうど 0 ℃ なら通知しない. */
    @Test
    fun detectAlertsFreezingBoundary() {
        assertEquals(setOf(OutdoorAlert.FREEZING), detectAlerts(reading(-0.1, 50.0), now))
        assertEquals(emptySet(), detectAlerts(reading(0.0, 50.0), now))
    }

    /** 湿度が 80 % を超えたら湿度が高い, ちょうど 80 % なら通知しない. */
    @Test
    fun detectAlertsHumidBoundary() {
        assertEquals(setOf(OutdoorAlert.HUMID), detectAlerts(reading(10.0, 80.1), now))
        assertEquals(emptySet(), detectAlerts(reading(10.0, 80.0), now))
    }

    /** 両方の状態は, 同時に起きる. 湿度がない測定値は, 湿度を判定しない. */
    @Test
    fun detectAlertsBothAndNoHumidity() {
        assertEquals(
            setOf(OutdoorAlert.FREEZING, OutdoorAlert.HUMID),
            detectAlerts(reading(-3.0, 90.0), now),
        )
        assertEquals(emptySet(), detectAlerts(reading(5.0, null), now))
    }

    /** 1 時間より古い測定値は判定せず (null), ちょうど 1 時間前は判定する. */
    @Test
    fun detectAlertsStale() {
        assertNull(detectAlerts(reading(-3.0, 90.0, ts = now - 3601L), now))
        assertEquals(
            setOf(OutdoorAlert.FREEZING, OutdoorAlert.HUMID),
            detectAlerts(reading(-3.0, 90.0, ts = now - 3600L), now),
        )
    }

    /** 通知した状態は除き, 新しい状態だけを求める. */
    @Test
    fun newAlertsExcludesNotified() {
        val both = setOf(OutdoorAlert.FREEZING, OutdoorAlert.HUMID)

        assertEquals(both, newAlerts(both, emptySet()))
        assertEquals(setOf(OutdoorAlert.HUMID), newAlerts(both, setOf(OutdoorAlert.FREEZING)))
        assertEquals(emptySet(), newAlerts(both, both))
    }
}
