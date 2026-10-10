/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.WindowInsets
import android.widget.TextView
import jp.thermon.core.formatReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZoneId

/** 画面の端と, 文字の間の余白 (dp). */
private const val CONTENT_PADDING_DP = 16

/**
 * 機器ごとの最新の測定値を表示する, 最小の画面 (雛形).
 *
 * 機器は, local.properties の thermon.nodes (コンマ区切りの MAC アドレス) で指定する.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var view: TextView

    /**
     * 画面を作って, 最新の測定値を取り出して, 表示する.
     *
     * @param savedInstanceState 保存した状態
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this)
        applySystemBarInsets()
        setContentView(view)

        val nodes =
            BuildConfig.NODES
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (BuildConfig.IDENTITY_POOL_ID.isEmpty() || nodes.isEmpty()) {
            view.text = getString(R.string.not_configured)
            return
        }
        view.text = getString(R.string.loading)
        scope.launch {
            val text =
                runCatching {
                    val repository = AwsClients.readings()
                    nodes
                        .map { node ->
                            repository
                                .latest(
                                    node,
                                )?.let { formatReading(it, ZoneId.systemDefault()) }
                                ?: "$node  (no data)"
                        }.joinToString("\n")
                }.getOrElse { "Error: ${it.message}" }
            runOnUiThread { view.text = text }
        }
    }

    /**
     * 文字が, システムバー (ステータスバーなど) と, アクションバーの下に, 隠れないようにする.
     *
     * Android 15 以降 (targetSdk 35 以上) は, 画面が, システムバーの下まで広がる (edge-to-edge).
     * システムバーの余白と, アクションバーの高さを, パディングに入れる. それ以前の版は, 広がらないので,
     * 何もしない.
     */
    private fun applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val density = resources.displayMetrics.density
        val padding = (CONTENT_PADDING_DP * density).toInt()
        val actionBarSize =
            TypedValue().let {
                theme.resolveAttribute(android.R.attr.actionBarSize, it, true)
                TypedValue.complexToDimensionPixelSize(it.data, resources.displayMetrics)
            }
        view.setOnApplyWindowInsetsListener { target, insets ->
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

    /** 画面を閉じるときに, 取り出し中の処理を止める. */
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
