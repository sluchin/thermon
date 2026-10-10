/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** 試験のタイムゾーン (日本標準時). */
private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

/** [formatReading] の単体テスト. */
class ReadingFormatTest {
    /** 温度, 湿度, 時刻 (指定したタイムゾーン) を, 1 行にする. */
    @Test
    fun formatReadingWithHumidity() {
        // 2026-10-10 05:05:09 UTC = 14:05:09 JST
        val reading = Reading("AA:BB:CC:DD:EE:01", 1791608709, "switchbot", 21.4, 57.0, 100.0)

        assertEquals(
            "AA:BB:CC:DD:EE:01  21.4 C  57.0 %  2026-10-10 14:05:09",
            formatReading(reading, JST),
        )
    }

    /** 湿度がないときは, 湿度の部分を, 出さない (0 ℃ 未満は, 符号を付ける). */
    @Test
    fun formatReadingWithoutHumidity() {
        val reading = Reading("AA:BB:CC:DD:EE:02", 0, null, -0.5, null, null)

        assertEquals("AA:BB:CC:DD:EE:02  -0.5 C  1970-01-01 09:00:00", formatReading(reading, JST))
    }
}
