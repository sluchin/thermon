/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.content.Context

/**
 * dp を, 画面の密度に合わせて, px にする.
 *
 * @receiver コンテキスト
 * @param value dp
 * @return px
 */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
