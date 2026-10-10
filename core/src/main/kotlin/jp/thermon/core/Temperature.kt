/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlin.math.abs

/** 10 分の 1 の単位の値から, 整数部と小数部を取り出すための値. */
private const val X10 = 10

/**
 * 温度 (10 分の 1 ℃ の単位) を, 表示用の文字列にする (例: 235 は "23.5 C", -5 は "-0.5 C").
 *
 * @param tempX10 温度 (℃ の 10 倍)
 * @return 表示用の文字列
 */
fun formatTemperature(tempX10: Int): String {
    // 0 ℃ 未満は, 整数部が 0 でも (-0.5 など), 符号を出す
    val sign = if (tempX10 < 0) "-" else ""
    val magnitude = abs(tempX10)
    return "$sign${magnitude / X10}.${magnitude % X10} C"
}
