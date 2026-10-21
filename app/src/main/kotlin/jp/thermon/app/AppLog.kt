/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.content.Context
import android.util.Log
import jp.thermon.core.LogLevel
import jp.thermon.core.RotatingLogFile
import jp.thermon.core.formatLogEntry
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 1 つのログファイルの上限 (バイト). */
private const val MAX_LOG_BYTES = 1024L * 1024L

/** 残す, 古いログファイルの数 (全部で, 約 4 MB). */
private const val MAX_LOG_BACKUPS = 3

/** 落ちたときに, ログファイルに書き終えるのを待つ時間 (ms). */
private const val CRASH_WRITE_TIMEOUT_MS = 1000L

/** 落ちたときのログのタグ. */
private const val CRASH_TAG = "Crash"

/**
 * アプリのログ. Android の logcat (`adb logcat -s Readings:D` のように, タグで絞って見る) と,
 * アプリのファイル (`files/logs/app.log`. 1 MB で切り替えて, 古いものを 3 つ残す) の両方に出す.
 * ファイルのログは, アプリの「Log」の画面 ([LogActivity]) で見て, 保存できる.
 *
 * - [d]: デバッグ版 (`BuildConfig.DEBUG`) だけ出す. リリース版では, 文字列も作らない (引数は, 文字列を
 *   返す関数).
 * - [i], [w], [e]: デバッグ版とリリース版の両方で出す. リリース版で出すのは, 最低限の情報 (何をしたか, 何匹か)
 *   と, 警告とエラーだけ.
 *
 * AWS の認証情報と, 鍵は, どのレベルでも, ログに書かない.
 * アプリが落ちたときの例外も, ファイルに書く ([init]). Google Play で公開したら,
 * Play Console の Android Vitals も, 例外と ANR を集める.
 */
object AppLog {
    /** ファイルに書くスレッド (画面を止めないように, 1 本で順に書く). */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "AppLog") }

    /** ログファイル ([init] の前は, null. logcat だけに出す). */
    @Volatile
    private var file: RotatingLogFile? = null

    /**
     * ファイルへの書き込みと, 落ちたときの記録を始める. アプリの起動のときに 1 度だけ呼ぶ ([ThermonApp]).
     *
     * @param context アプリの Context
     */
    fun init(context: Context) {
        file =
            RotatingLogFile(File(context.filesDir, "logs/app.log"), MAX_LOG_BYTES, MAX_LOG_BACKUPS)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeAndWait(LogLevel.ERROR, CRASH_TAG, "Uncaught exception in ${thread.name}", error)
            // Android の通常の処理 (logcat に出して, アプリを終える) に渡す
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * ログファイルを返す (画面で読むため).
     *
     * @return ログファイル ([init] の前は, null)
     */
    fun logFile(): RotatingLogFile? = file

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
            val text = message()
            Log.d(tag, text)
            write(LogLevel.DEBUG, tag, text, null)
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
        write(LogLevel.INFO, tag, message, null)
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
        write(LogLevel.WARN, tag, message, error)
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
        write(LogLevel.ERROR, tag, message, error)
    }

    /**
     * ログファイルに書く (書くのは, 別のスレッド. 日時は, 呼んだときのもの).
     *
     * @param level 重大度
     * @param tag タグ
     * @param message ログ
     * @param error 原因の例外 (なければ, null)
     */
    private fun write(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        val target = file ?: return
        val text = formatLogEntry(ZonedDateTime.now(), level, tag, message, error)
        writer.execute { append(target, text) }
    }

    /**
     * ログファイルに書いて, 書き終えるのを待つ (落ちたとき. 先に頼んだログも, 順に書き終わる).
     *
     * @param level 重大度
     * @param tag タグ
     * @param message ログ
     * @param error 例外
     */
    private fun writeAndWait(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable,
    ) {
        val target = file ?: return
        val text = formatLogEntry(ZonedDateTime.now(), level, tag, message, error)
        try {
            writer
                .submit {
                    append(
                        target,
                        text,
                    )
                }.get(CRASH_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (failure: ExecutionException) {
            Log.w(CRASH_TAG, "Could not write the crash", failure)
        } catch (failure: TimeoutException) {
            Log.w(CRASH_TAG, "Timed out writing the crash", failure)
        } catch (failure: InterruptedException) {
            Log.w(CRASH_TAG, "Interrupted writing the crash", failure)
        }
    }

    /**
     * ログファイルに書く. 書けなくても (容量が一杯など), logcat には出ているので, 続ける.
     *
     * @param target ログファイル
     * @param text 書く文字列
     */
    private fun append(
        target: RotatingLogFile,
        text: String,
    ) {
        try {
            target.append(text)
        } catch (failure: IOException) {
            Log.w("AppLog", "Could not write the log file", failure)
        }
    }
}
