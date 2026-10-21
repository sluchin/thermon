/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.app.Application

/** ログのタグ. */
private const val TAG = "App"

/** アプリ全体の初期化 (画面と, 通知の確認 ([OutdoorAlertWorker]) の両方より先に動く). */
class ThermonApp : Application() {
    /** ログのファイルへの書き込みと, 落ちたときの記録を始める. */
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(TAG, "Started (${BuildConfig.VERSION_NAME})")
    }
}
