/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** [formatTemperature] の単体テスト. */
class TemperatureTest {
    /** 0 ℃ 以上の温度は, 符号なしで表示する. */
    @Test
    fun formatTemperaturePositive() {
        assertEquals("23.5 C", formatTemperature(235))
    }

    /** 0 ℃ 未満の温度は, 整数部が 0 でも, 符号を付けて表示する. */
    @Test
    fun formatTemperatureNegative() {
        assertEquals("-0.5 C", formatTemperature(-5))
    }
}
