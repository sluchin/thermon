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
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import jp.thermon.core.ChartRange
import jp.thermon.core.ReadingsRepository
import jp.thermon.core.loadSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/** 画面の端と, 内容の間の余白 (dp). */
private const val CONTENT_PADDING_DP = 16

/** ミリ秒を, 秒にするための値. */
private const val MILLIS_PER_SECOND = 1000L

/**
 * 機器ごとに, 最新の測定値と, 温度と湿度のグラフを表示する画面.
 *
 * 機器は, local.properties の thermon.nodes (コンマ区切りの MAC アドレス) で指定する.
 * グラフの範囲 (1 時間, 6 時間, 24 時間) は, 上のボタンで選ぶ.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var root: ScrollView
    private var sections: List<NodeSection> = emptyList()
    private var buttons: Map<ChartRange, Button> = emptyMap()
    private var repository: ReadingsRepository? = null
    private var range = ChartRange.HOUR_6
    private var loadJob: Job? = null

    /**
     * 画面を作って, 測定値を取り出して, 表示する.
     *
     * @param savedInstanceState 保存した状態
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root =
            ScrollView(this).apply {
                clipToPadding = false
                addView(content)
            }
        applySystemBarInsets()
        setContentView(root)

        val nodes =
            BuildConfig.NODES
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (BuildConfig.IDENTITY_POOL_ID.isEmpty() || nodes.isEmpty()) {
            content.addView(TextView(this).apply { text = getString(R.string.not_configured) })
            return
        }
        content.addView(createRangeBar())
        sections =
            nodes.map {
                NodeSection(this, it).also { section ->
                    content.addView(section.container)
                }
            }
        repository = AwsClients.readings()
        reload()
    }

    /**
     * 範囲を選ぶボタン (1 時間, 6 時間, 24 時間) を並べた行を作る.
     *
     * @return ボタンの行
     */
    private fun createRangeBar(): LinearLayout {
        val labels =
            mapOf(
                ChartRange.HOUR_1 to R.string.range_1h,
                ChartRange.HOUR_6 to R.string.range_6h,
                ChartRange.HOUR_24 to R.string.range_24h,
            )
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons =
            labels.mapValues { (chartRange, labelId) ->
                Button(this).apply {
                    text = getString(labelId)
                    setOnClickListener { selectRange(chartRange) }
                    bar.addView(
                        this,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
            }
        updateButtons()
        return bar
    }

    /**
     * 範囲を選んで, 測定値を取り出し直す.
     *
     * @param selected 選んだ範囲
     */
    private fun selectRange(selected: ChartRange) {
        range = selected
        updateButtons()
        reload()
    }

    /** 選んでいる範囲のボタンを, 押せなくする (どれを選んでいるかが, 分かる). */
    private fun updateButtons() {
        buttons.forEach { (chartRange, button) -> button.isEnabled = chartRange != range }
    }

    /**
     * 機器ごとに, 測定値を取り出して, 表示する (取り出し中の処理があれば, 止める).
     *
     * 取り出しに失敗した機器は, エラーのメッセージを表示する (ほかの機器は, 続ける).
     */
    private fun reload() {
        val source = repository ?: return
        val selected = range
        loadJob?.cancel()
        sections.forEach { it.showLoading() }
        loadJob =
            scope.launch {
                val nowTs = System.currentTimeMillis() / MILLIS_PER_SECOND
                for (section in sections) {
                    val result = runCatching { loadSnapshot(source, section.node, selected, nowTs) }
                    // 範囲を選び直して, 止められたときは, 古い結果を表示しない
                    ensureActive()
                    runOnUiThread { section.show(result, ZoneId.systemDefault()) }
                }
            }
    }

    /**
     * 内容が, システムバー (ステータスバーなど) と, アクションバーの下に, 隠れないようにする.
     *
     * Android 15 以降 (targetSdk 35 以上) は, 画面が, システムバーの下まで広がる (edge-to-edge).
     * システムバーの余白と, アクションバーの高さを, パディングに入れる. それ以前の版は, 広がらないので,
     * 何もしない.
     */
    private fun applySystemBarInsets() {
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

    /** 画面を閉じるときに, 取り出し中の処理を止める. */
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
