/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.app.Activity
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets

/** 画面の端と, 内容の間の余白 (dp). */
private const val CONTENT_PADDING_DP = 16

/**
 * 内容が, システムバー (ステータスバーなど) と, アクションバーの下に, 隠れないようにする.
 *
 * Android 15 以降 (targetSdk 35 以上) は, 画面が, システムバーの下まで広がる (edge-to-edge).
 * システムバーの余白と, アクションバーの高さを, パディングに入れる. それ以前の版は, 広がらないので,
 * 何もしない.
 *
 * @receiver 画面
 * @param root 画面の一番外側の View
 */
fun Activity.padForSystemBars(root: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return
    }
    val padding = dp(CONTENT_PADDING_DP)
    val actionBarSize =
        TypedValue().let {
            theme.resolveAttribute(android.R.attr.actionBarSize, it, true)
            TypedValue.complexToDimensionPixelSize(it.data, resources.displayMetrics)
        }
    root.setOnApplyWindowInsetsListener { target, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        target.setPadding(
            bars.left + padding,
            bars.top + actionBarSize + padding,
            bars.right + padding,
            bars.bottom + padding,
        )
        insets
    }
}
