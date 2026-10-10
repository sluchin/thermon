/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** 受信した時刻の表示の形式 (例: 2026-10-10 14:05:09). */
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 温度を, 10 分の 1 ℃ の単位の整数にするための倍率. */
private const val TEMPERATURE_SCALE = 10

/**
 * 測定値を, 1 行の表示用の文字列にする (例: AA:BB:CC:DD:EE:01  21.4 C  57.0 %  2026-10-10 14:05:09).
 *
 * 湿度がないときは, 湿度の部分を, 出さない.
 *
 * @param reading 測定値
 * @param zone 時刻を表示するタイムゾーン
 * @return 表示用の文字列
 */
fun formatReading(
    reading: Reading,
    zone: ZoneId,
): String {
    val temperature = formatTemperature((reading.temperatureC * TEMPERATURE_SCALE).roundToInt())
    val humidity = reading.humidity?.let { "  ${"%.1f".format(Locale.ROOT, it)} %" }.orEmpty()
    val time = TIME_FORMAT.format(Instant.ofEpochSecond(reading.ts).atZone(zone))
    return "${reading.node}  $temperature$humidity  $time"
}
