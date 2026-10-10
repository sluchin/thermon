/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import jp.thermon.core.ChartSeries
import jp.thermon.core.formatChartTime
import jp.thermon.core.formatChartValue
import java.time.ZoneId

/** 文字の大きさ (sp). */
private const val TEXT_SIZE_SP = 11f

/** 線の太さ (dp). */
private const val LINE_WIDTH_DP = 2f

/** 補助線の太さ (dp). */
private const val GRID_WIDTH_DP = 1f

/** 点が 1 つだけの線の部分を描く円の半径 (dp). */
private const val POINT_RADIUS_DP = 3f

/** 縦軸の目盛りの文字を置く, 左の余白 (dp). */
private const val PADDING_LEFT_DP = 44f

/** 右の余白 (dp). */
private const val PADDING_RIGHT_DP = 8f

/** タイトルを置く, 上の余白 (dp). 縦軸の上限の値が, タイトルと重ならない高さにする. */
private const val PADDING_TOP_DP = 32f

/** タイトルの文字の基準線 (ベースライン) の, 上端からの位置 (dp). */
private const val TITLE_BASELINE_DP = 14f

/** 横軸の時刻の文字を置く, 下の余白 (dp). */
private const val PADDING_BOTTOM_DP = 20f

/** 文字と線の間の隙間 (dp). */
private const val TEXT_GAP_DP = 4f

/** 補助線の色の透明度 (0 から 255). */
private const val GRID_ALPHA = 64

/** 文字の色が, テーマから取れなかったときの色. */
private const val FALLBACK_TEXT_COLOR = Color.GRAY

/** 補助線の数 (上端, 中央, 下端). */
private const val GRID_LINES = 3

/** 補助線の中央の位置を求めるための値. */
private const val HALF = 2f

/** グラフの描く範囲 (View の中の, 左右上下の位置. px). */
private class Plot(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

/**
 * 折れ線グラフ (1 本の線と, 縦軸の上限と下限, 横軸の開始と終了の時刻).
 *
 * [configure] でタイトルなどを決めて, [setChart] で線を渡す. 線が null のときは, 文字だけを表示する.
 *
 * @param context コンテキスト
 * @param attrs 属性
 */
class LineChartView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val density = resources.displayMetrics.density
        private val textColor = themeTextColor(context)
        private val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = LINE_WIDTH_DP * density
                strokeJoin = Paint.Join.ROUND
            }
        private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val gridPaint =
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = GRID_WIDTH_DP * density
                color =
                    Color.argb(
                        GRID_ALPHA,
                        Color.red(textColor),
                        Color.green(textColor),
                        Color.blue(textColor),
                    )
            }
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize =
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        TEXT_SIZE_SP,
                        resources.displayMetrics,
                    )
            }
        private val path = Path()
        private var plot = Plot()
        private var title = ""
        private var unit = ""
        private var emptyText = ""
        private var series: ChartSeries? = null
        private var zone: ZoneId = ZoneId.systemDefault()

        /**
         * タイトルと単位と線の色を決める.
         *
         * @param title グラフの上に表示するタイトル
         * @param unit 縦軸の値に付ける単位
         * @param lineColor 線の色
         */
        fun configure(
            title: String,
            unit: String,
            lineColor: Int,
        ) {
            this.title = title
            this.unit = unit
            linePaint.color = lineColor
            pointPaint.color = lineColor
            invalidate()
        }

        /**
         * 線を渡して, 描き直す.
         *
         * @param series 線. null のときは, [emptyText] だけを表示する
         * @param zone 横軸の時刻を表示するタイムゾーン
         * @param emptyText 線がないときに表示する文字
         */
        fun setChart(
            series: ChartSeries?,
            zone: ZoneId,
            emptyText: String,
        ) {
            this.series = series
            this.zone = zone
            this.emptyText = emptyText
            invalidate()
        }

        /**
         * 大きさが決まったら, グラフを描く範囲を求める (描くたびに, オブジェクトを作らないため).
         *
         * @param w 幅
         * @param h 高さ
         * @param oldw 前の幅
         * @param oldh 前の高さ
         */
        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            plot =
                Plot(
                    left = PADDING_LEFT_DP * density,
                    top = PADDING_TOP_DP * density,
                    right = w - PADDING_RIGHT_DP * density,
                    bottom = h - PADDING_BOTTOM_DP * density,
                )
        }

        /**
         * タイトル, 補助線, 目盛り, 線を描く.
         *
         * @param canvas 描く先
         */
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(title, 0f, TITLE_BASELINE_DP * density, textPaint)
            for (i in 0 until GRID_LINES) {
                val y = plot.top + (plot.bottom - plot.top) * i / (GRID_LINES - 1)
                canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            }
            val current = series
            if (current == null) {
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    emptyText,
                    (plot.left + plot.right) / HALF,
                    (plot.top + plot.bottom) / HALF,
                    textPaint,
                )
                return
            }
            drawLabels(canvas, current)
            drawLines(canvas, current)
        }

        /**
         * 縦軸の上限と下限, 横軸の開始と終了の時刻を描く.
         *
         * @param canvas 描く先
         * @param current 線
         */
        private fun drawLabels(
            canvas: Canvas,
            current: ChartSeries,
        ) {
            val gap = TEXT_GAP_DP * density
            val textHeight = textPaint.textSize
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                label(current.maxValue),
                plot.left - gap,
                plot.top + textHeight / HALF,
                textPaint,
            )
            canvas.drawText(
                label(current.minValue),
                plot.left - gap,
                plot.bottom + textHeight / HALF,
                textPaint,
            )
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                formatChartTime(current.window.fromTs, zone),
                plot.left,
                height - gap,
                textPaint,
            )
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                formatChartTime(current.window.toTs, zone),
                plot.right,
                height - gap,
                textPaint,
            )
        }

        /**
         * 線の部分ごとに, 折れ線 (点が 1 つだけなら, 円) を描く.
         *
         * @param canvas 描く先
         * @param current 線
         */
        private fun drawLines(
            canvas: Canvas,
            current: ChartSeries,
        ) {
            val timeSpan = (current.window.toTs - current.window.fromTs).toFloat()
            val valueSpan = (current.maxValue - current.minValue).toFloat()
            for (segment in current.segments) {
                path.reset()
                for ((i, point) in segment.withIndex()) {
                    val x =
                        plot.left +
                            (point.ts - current.window.fromTs) / timeSpan * (plot.right - plot.left)
                    val y =
                        plot.bottom -
                            (point.value - current.minValue).toFloat() / valueSpan *
                            (plot.bottom - plot.top)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    if (segment.size == 1) {
                        canvas.drawCircle(x, y, POINT_RADIUS_DP * density, pointPaint)
                    }
                }
                canvas.drawPath(path, linePaint)
            }
        }

        /**
         * 縦軸の値に, 単位を付けた文字列にする.
         *
         * @param value 値
         * @return 文字列 (例: 21.4 °C)
         */
        private fun label(value: Double): String = "${formatChartValue(value)} $unit"
    }

/**
 * テーマの文字の色を取り出す (暗いテーマでも, 明るいテーマでも, 読める色にする).
 *
 * @param context コンテキスト
 * @return 文字の色. 取れなかったときは, 灰色
 */
private fun themeTextColor(context: Context): Int {
    val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
    try {
        return attributes.getColor(0, FALLBACK_TEXT_COLOR)
    } finally {
        attributes.recycle()
    }
}
