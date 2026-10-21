/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import jp.thermon.core.LogLevel
import jp.thermon.core.filterLog
import jp.thermon.core.parseLog
import jp.thermon.core.renderLog
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** ログのタグ. */
private const val TAG = "LogScreen"

/** 画面に出す, ログの末尾の最大 (バイト). */
private const val SHOW_BYTES = 256L * 1024L

/** 保存する, ログの最大 (バイト. 古いファイルも含めて, 全部). */
private const val SAVE_BYTES = 8L * 1024L * 1024L

/** 共有 (メールなど) で送る, ログの末尾の最大 (文字. Android の受け渡しの上限より小さくする). */
private const val SHARE_CHARS = 100_000

/** 保存の画面の結果を見分ける番号. */
private const val REQUEST_SAVE = 1

/** ログの文字の大きさ (sp). */
private const val LOG_TEXT_SP = 12f

/** 保存するファイルの名前に入れる日時の形. */
private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

/** 絞り込みの選択肢 (画面の文字と, 出す最も低い重大度). 最初が既定. */
private val LEVEL_CHOICES =
    listOf(
        R.string.log_level_info to LogLevel.INFO,
        R.string.log_level_warn to LogLevel.WARN,
        R.string.log_level_error to LogLevel.ERROR,
        R.string.log_level_all to LogLevel.DEBUG,
    )

/**
 * アプリのログ ([AppLog] がファイルに書いたもの) を見る画面. 新しい順に出す.
 *
 * 重大度で絞り込み, 保存 (Android の保存の画面. Google Drive も選べる), 共有 (メールなど), 消すことができる.
 */
class LogActivity : Activity() {
    /** ファイルを読み書きするスレッド (画面を止めない). */
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var logView: TextView
    private var minimum = LEVEL_CHOICES.first().second

    /**
     * 画面を作って, ログを読む.
     *
     * @param savedInstanceState 保存した状態
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.log_title)
        logView =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LOG_TEXT_SP)
                setTextIsSelectable(true)
            }
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(createToolBar())
                addView(
                    ScrollView(this@LogActivity).apply { addView(logView) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }
        padForSystemBars(root)
        setContentView(root)
        reload()
    }

    /**
     * 上のボタンの並び (絞り込み, 保存, 共有, 消す) を作る.
     *
     * @return ボタンの並び
     */
    private fun createToolBar(): LinearLayout {
        val levels =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@LogActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        LEVEL_CHOICES.map { getString(it.first) },
                    )
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: android.view.View?,
                            position: Int,
                            id: Long,
                        ) {
                            minimum = LEVEL_CHOICES[position].second
                            reload()
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
            }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(levels)
            addView(button(R.string.log_save) { save() })
            addView(button(R.string.log_share) { share() })
            addView(button(R.string.log_clear) { confirmClear() })
        }
    }

    /**
     * ボタンを作る.
     *
     * @param label 文字
     * @param onClick 押したときの処理
     * @return ボタン
     */
    private fun button(
        label: Int,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = getString(label)
            setOnClickListener { onClick() }
        }

    /** ログを読み直して, 絞り込んで, 新しい順に出す. */
    private fun reload() {
        val file = AppLog.logFile() ?: return
        val level = minimum
        io.execute {
            val text =
                try {
                    renderLog(filterLog(parseLog(file.readTail(SHOW_BYTES)), level))
                } catch (failure: IOException) {
                    AppLog.w(TAG, "Could not read the log", failure)
                    getString(R.string.log_read_error, failure.message)
                }
            runOnUiThread { logView.text = text.ifEmpty { getString(R.string.log_empty) } }
        }
    }

    /** Android の保存の画面を開く (保存は, [onActivityResult]). */
    private fun save() {
        val name = "thermon-log-${LocalDateTime.now().format(FILE_STAMP)}.txt"
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(intent, REQUEST_SAVE)
    }

    /**
     * 保存の画面で選んだ場所に, ログ (古いファイルも含めて, 全部) を書く.
     *
     * @param requestCode 開いた画面の番号
     * @param resultCode 結果
     * @param data 選んだ場所
     */
    @Deprecated("Activity の結果は, この画面では, 保存の 1 つだけなので, 従来の方法で受ける")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data.takeIf { requestCode == REQUEST_SAVE && resultCode == RESULT_OK }
        val file = AppLog.logFile()
        if (uri == null || file == null) {
            return
        }
        io.execute {
            val message =
                try {
                    val text = file.readTail(SAVE_BYTES)
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    AppLog.i(TAG, "Saved the log")
                    getString(R.string.log_saved)
                } catch (failure: IOException) {
                    AppLog.e(TAG, "Could not save the log", failure)
                    getString(R.string.log_save_error, failure.message)
                }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    /** 今出しているログ (絞り込んだもの. 新しい順) を, ほかのアプリ (メールなど) に送る. */
    private fun share() {
        val text = logView.text.toString().take(SHARE_CHARS)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_title))
                .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    /** 消してよいかを確かめてから, ログを消す. */
    private fun confirmClear() {
        AlertDialog
            .Builder(this)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                val file = AppLog.logFile() ?: return@setPositiveButton
                io.execute {
                    file.clear()
                    AppLog.i(TAG, "Cleared the log")
                    runOnUiThread { reload() }
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 画面を閉じるときに, 読み書きのスレッドを止める. */
    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
