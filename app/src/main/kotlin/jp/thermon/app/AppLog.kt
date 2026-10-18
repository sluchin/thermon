/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.util.Log

/**
 * アプリのログ (Android の logcat に出す. `adb logcat -s Readings:D` のように, タグで絞って見る).
 *
 * - [d]: デバッグ版 (`BuildConfig.DEBUG`) だけ出す. リリース版では, 文字列も作らない (引数は, 文字列を
 *   返す関数).
 * - [i], [w], [e]: デバッグ版とリリース版の両方で出す. リリース版で出すのは, 最低限の情報 (何をしたか, 何匹か)
 *   と, 警告とエラーだけ.
 *
 * AWS の認証情報と, 鍵は, どのレベルでも, ログに書かない.
 * アプリが落ちたときの例外は, Android が logcat (`AndroidRuntime`) に出す. Google Play で公開したら,
 * Play Console の Android Vitals が, 例外と ANR を集める (コードは要らない).
 */
object AppLog {
    /**
     * デバッグのログ (デバッグ版だけ).
     *
     * @param tag タグ (機能の名前. `Readings` など)
     * @param message ログの文字列を返す関数 (デバッグ版のときだけ, 呼ぶ)
     */
    fun d(
        tag: String,
        message: () -> String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }

    /**
     * 情報のログ (リリース版でも出す. 最低限にする).
     *
     * @param tag タグ
     * @param message ログ
     */
    fun i(
        tag: String,
        message: String,
    ) {
        Log.i(tag, message)
    }

    /**
     * 警告のログ (リリース版でも出す. 失敗したが, 続けられるとき).
     *
     * @param tag タグ
     * @param message ログ
     * @param error 原因の例外 (なければ, null)
     */
    fun w(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        Log.w(tag, message, error)
    }

    /**
     * エラーのログ (リリース版でも出す. 利用者の操作が, 失敗したとき).
     *
     * @param tag タグ
     * @param message ログ
     * @param error 原因の例外 (なければ, null)
     */
    fun e(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        Log.e(tag, message, error)
    }
}
