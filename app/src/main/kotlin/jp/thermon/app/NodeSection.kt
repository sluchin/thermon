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
import jp.thermon.core.NodeSnapshot
import jp.thermon.core.formatReading
import java.time.ZoneId

/** 温度の線の色 (橙). */
private const val TEMPERATURE_COLOR = 0xFFE57300.toInt()

/** 湿度の線の色 (青). */
private const val HUMIDITY_COLOR = 0xFF2196F3.toInt()

/** 機器と機器の間の余白 (dp). */
private const val SECTION_GAP_DP = 24

/** グラフの高さ (dp). */
private const val CHART_HEIGHT_DP = 180

/**
 * 1 台の機器の表示 (最新の測定値の 1 行と, 温度と湿度のグラフ).
 *
 * @property context コンテキスト
 * @property node 機器の識別子
 */
class NodeSection(
    private val context: Context,
    val node: String,
) {
    /** この機器の表示を, まとめた View. */
    val container =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(SECTION_GAP_DP), 0, 0)
        }
    private val header = TextView(context)
    private val temperature =
        createChart(R.string.temperature_title, R.string.temperature_unit, TEMPERATURE_COLOR)
    private val humidity =
        createChart(R.string.humidity_title, R.string.humidity_unit, HUMIDITY_COLOR)

    init {
        container.addView(header)
        container.addView(temperature, chartParams())
        container.addView(humidity, chartParams())
    }

    /** 取り出している間の表示にする. */
    fun showLoading() {
        val loading = context.getString(R.string.loading)
        header.text = loading
        temperature.setChart(null, ZoneId.systemDefault(), loading)
        humidity.setChart(null, ZoneId.systemDefault(), loading)
    }

    /**
     * 取り出した結果を表示する (失敗したときは, エラーのメッセージ).
     *
     * @param result 取り出した結果
     * @param zone 時刻を表示するタイムゾーン
     */
    fun show(
        result: Result<NodeSnapshot>,
        zone: ZoneId,
    ) {
        val noData = context.getString(R.string.chart_no_data)
        val snapshot = result.getOrNull()
        header.text =
            if (snapshot == null) {
                context.getString(R.string.error, node, result.exceptionOrNull()?.message)
            } else {
                snapshot.latest?.let { formatReading(it, zone) }
                    ?: context.getString(R.string.no_data, node)
            }
        temperature.setChart(snapshot?.temperature, zone, noData)
        humidity.setChart(snapshot?.humidity, zone, noData)
    }

    /**
     * グラフを作る.
     *
     * @param titleId タイトルの文字列のリソース
     * @param unitId 単位の文字列のリソース
     * @param color 線の色
     * @return グラフ
     */
    private fun createChart(
        titleId: Int,
        unitId: Int,
        color: Int,
    ): LineChartView =
        LineChartView(context).apply {
            configure(context.getString(titleId), context.getString(unitId), color)
        }

    /**
     * グラフの大きさ (幅は, 画面いっぱい. 高さは, 決まった値).
     *
     * @return レイアウトのパラメータ
     */
    private fun chartParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(CHART_HEIGHT_DP))
}
