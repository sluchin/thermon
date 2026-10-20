/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import jp.thermon.core.ChartSeries
import jp.thermon.core.NodeSnapshot
import jp.thermon.core.NodeSpec
import jp.thermon.core.formatReading
import java.time.ZoneId

/** 機器の線の色 (機器の並びの順. 足りなければ, 繰り返す): 橙, 青, 緑, 紫. */
private val NODE_COLORS =
    intArrayOf(0xFFE57300.toInt(), 0xFF2196F3.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt())

/** ボタンの行などの上の余白 (dp). */
private const val SECTION_GAP_DP = 8

/** グラフの高さ (dp). */
private const val CHART_HEIGHT_DP = 200

/** ボタンの幅の重み (ボタンの数で, 均等に分ける). */
private const val BUTTON_WEIGHT = 1f

/**
 * 温度と湿度と気圧の 3 つのグラフと, 機器を選ぶボタン (室内, 室外など) と, 機器ごとの最新の測定値.
 *
 * ボタンを押した機器の線が, 3 つのグラフに重なって表示される (もう一度押すと, 消える). 気圧のグラフは,
 * 気圧センサのある機器 (Thermo ノード) の線だけになる.
 * 最初は, 全ての機器を表示する.
 *
 * @property context コンテキスト
 * @property specs 機器 (識別子と, 表示名). 並びの順に, 線の色が決まる
 */
class ChartsPanel(
    private val context: Context,
    private val specs: List<NodeSpec>,
) {
    /** 表示をまとめた View. */
    val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val buttons = specs.mapIndexed { index, spec -> createButton(index, spec) }
    private val readings = specs.map { TextView(context) }
    private val temperature = createChart(R.string.temperature_title, R.string.temperature_unit)
    private val humidity = createChart(R.string.humidity_title, R.string.humidity_unit)
    private val pressure = createChart(R.string.pressure_title, R.string.pressure_unit)
    private var results: Map<String, Result<NodeSnapshot>> = emptyMap()
    private var zone: ZoneId = ZoneId.systemDefault()
    private var loading = false

    init {
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach {
            bar.addView(
                it,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, BUTTON_WEIGHT),
            )
        }
        container.addView(bar)
        readings.forEach { container.addView(it) }
        container.addView(temperature, chartParams())
        container.addView(humidity, chartParams())
        container.addView(pressure, chartParams())
    }

    /** 取り出している間の表示にする. */
    fun showLoading() {
        results = emptyMap()
        loading = true
        render()
    }

    /**
     * 取り出した結果を表示する (取り出し済みの機器だけ. 失敗した機器は, エラーのメッセージ).
     *
     * @param loaded 機器の識別子ごとの, 取り出した結果
     * @param zone 時刻を表示するタイムゾーン
     */
    fun show(
        loaded: Map<String, Result<NodeSnapshot>>,
        zone: ZoneId,
    ) {
        results = loaded
        this.zone = zone
        loading = false
        render()
    }

    /** 選んだ機器の線を, 3 つのグラフに描き直し, 機器ごとの最新の測定値を表示する. */
    private fun render() {
        val loadingText = context.getString(R.string.loading)
        specs.forEachIndexed { index, spec ->
            readings[index].text = readingText(spec, results[spec.node], loadingText)
        }
        val noData = if (loading) loadingText else context.getString(R.string.chart_no_data)
        temperature.setChart(lines(NodeSnapshot::temperature), zone, noData)
        humidity.setChart(lines(NodeSnapshot::humidity), zone, noData)
        pressure.setChart(lines(NodeSnapshot::pressure), zone, noData)
    }

    /**
     * 機器の最新の測定値の 1 行を作る.
     *
     * @param spec 機器
     * @param result 取り出した結果 (まだなら, null)
     * @param loadingText 取り出し中の文字
     * @return 表示する文字列 (表示名があれば, 先頭に付ける)
     */
    private fun readingText(
        spec: NodeSpec,
        result: Result<NodeSnapshot>?,
        loadingText: String,
    ): String {
        val snapshot = result?.getOrNull()
        val body =
            when {
                result == null -> loadingText
                snapshot == null ->
                    context.getString(
                        R.string.error,
                        spec.node,
                        result.exceptionOrNull()?.message,
                    )
                else ->
                    snapshot.latest?.let { formatReading(it, zone) }
                        ?: context.getString(R.string.no_data, spec.node)
            }
        val warning =
            snapshot
                ?.syncError
                ?.let {
                    "  " + context.getString(R.string.sync_error, it)
                }.orEmpty()
        return (spec.label?.let { "$it  $body" } ?: body) + warning
    }

    /**
     * 選んでいる機器の線を集める (取り出せなかった機器と, 範囲に値のない機器は, 除く).
     *
     * @param pick 機器の表示内容から, 温度か湿度か気圧の線を取り出す関数
     * @return 線 (機器の並びの順)
     */
    private fun lines(pick: (NodeSnapshot) -> ChartSeries?): List<ChartLine> =
        specs.mapIndexedNotNull { index, spec ->
            val series = results[spec.node]?.getOrNull()?.let(pick)
            if (buttons[index].isChecked && series != null) {
                ChartLine(spec.label ?: spec.node, colorOf(index), series)
            } else {
                null
            }
        }

    /**
     * 機器を選ぶボタンを作る (押すと, その機器の線を, 出したり消したりする).
     *
     * @param index 機器の位置
     * @param spec 機器
     * @return ボタン (最初は, 選んだ状態)
     */
    private fun createButton(
        index: Int,
        spec: NodeSpec,
    ): ToggleButton =
        ToggleButton(context).apply {
            val name = spec.label ?: spec.node
            textOn = name
            textOff = name
            text = name
            setTextColor(colorOf(index))
            isChecked = true
            setOnCheckedChangeListener { _, _ -> render() }
        }

    /**
     * グラフを作る.
     *
     * @param titleId タイトルの文字列のリソース
     * @param unitId 単位の文字列のリソース
     * @return グラフ
     */
    private fun createChart(
        titleId: Int,
        unitId: Int,
    ): LineChartView =
        LineChartView(context).apply {
            configure(context.getString(titleId), context.getString(unitId))
        }

    /**
     * グラフの大きさ (幅は, 画面いっぱい. 高さは, 決まった値).
     *
     * @return レイアウトのパラメータ
     */
    private fun chartParams(): LinearLayout.LayoutParams =
        LinearLayout
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(CHART_HEIGHT_DP),
            ).apply {
                topMargin = context.dp(SECTION_GAP_DP)
            }

    /**
     * 機器の線の色.
     *
     * @param index 機器の位置
     * @return 色 (色の数を超えたら, 繰り返す)
     */
    private fun colorOf(index: Int): Int = NODE_COLORS[index % NODE_COLORS.size]
}
