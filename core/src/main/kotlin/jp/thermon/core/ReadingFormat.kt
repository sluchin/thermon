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

/** グラフの横軸の時刻の表示の形式 (例: 10-10 14:05). */
private val CHART_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

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

/**
 * グラフの横軸の時刻を, 表示用の文字列にする (例: 10-10 14:05).
 *
 * @param ts 時刻 (UNIX 時刻 (s))
 * @param zone 時刻を表示するタイムゾーン
 * @return 表示用の文字列
 */
fun formatChartTime(
    ts: Long,
    zone: ZoneId,
): String = CHART_TIME_FORMAT.format(Instant.ofEpochSecond(ts).atZone(zone))

/**
 * グラフの縦軸の値を, 小数点以下 1 桁の文字列にする (例: 21.4). 単位は, 付けない.
 *
 * @param value 値
 * @return 表示用の文字列
 */
fun formatChartValue(value: Double): String = "%.1f".format(Locale.ROOT, value)
