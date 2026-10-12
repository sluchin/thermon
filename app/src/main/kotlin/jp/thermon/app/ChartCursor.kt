/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import jp.thermon.core.ChartAxis
import jp.thermon.core.ChartPoint
import jp.thermon.core.nearestPoint
import jp.thermon.core.timeAtFraction

/** 長押しで出す, 値の表示の内側の余白 (dp). */
private const val READOUT_PADDING_DP = 6f

/** 値の表示の行と行の間の隙間 (dp). */
private const val ROW_GAP_DP = 4f

/** 長押しで出す, 値の表示の背景の透明度 (0 から 255). */
private const val READOUT_BACKGROUND_ALPHA = 220

/** 文字の色が明るいか暗いかを分ける, 明るさの境目 (0 から 1). */
private const val LUMINANCE_HALF = 0.5f

/** 2 で割って, 中央や半分を求めるための値. */
private const val HALF = 2f

/** グラフの描く範囲 (View の中の, 左右上下の位置. px). */
internal class Plot(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

/**
 * 長押しした位置の, 縦線の位置と, 表示する内容.
 *
 * @property x 縦線の x 座標 (px)
 * @property ts 縦線の時刻 (UNIX 時刻 (s))
 * @property entries 線ごとの, 名前と色と, 最も近い点 (点がなければ null)
 */
internal class Cursor(
    val x: Float,
    val ts: Long,
    val entries: List<Triple<String, Int, ChartPoint?>>,
)

/**
 * 指の位置から, 縦線の位置と, 線ごとの最も近い点を求める.
 *
 * @param axis 共通の軸
 * @param touch 指の x 座標 (px)
 * @return 縦線の位置と内容
 */
internal fun cursorAt(
    plot: Plot,
    axis: ChartAxis,
    lines: List<ChartLine>,
    touch: Float,
): Cursor {
    val width = plot.right - plot.left
    val fraction = ((touch - plot.left) / width).toDouble()
    val touchTs = timeAtFraction(axis.window, fraction)
    val entries =
        lines.map { Triple(it.name, it.color, nearestPoint(it.series, touchTs)) }
    val snapped = entries.firstNotNullOfOrNull { it.third }
    val ts = snapped?.ts ?: touchTs
    val span = (axis.window.toTs - axis.window.fromTs).toFloat()
    val x = plot.left + (ts - axis.window.fromTs) / span * width
    return Cursor(x, ts, entries)
}

/**
 * 時刻と値の表 (背景つき) を描く.
 *
 * @property textPaint 文字の塗り
 * @property density 画面の密度
 * @property textColor 文字の既定の色 (背景は, この色と反対の明るさにする)
 */
internal class ReadoutPainter(
    private val textPaint: Paint,
    private val density: Float,
    private val textColor: Int,
) {
    private val background = Paint().apply { style = Paint.Style.FILL }

    /**
     * 表を, 縦線の反対側の半分に描く (指で隠れないように).
     *
     * @param canvas 描く先
     * @param plot グラフの描く範囲
     * @param cursorX 縦線の x 座標
     * @param rows 行の文字と色
     */
    fun draw(
        canvas: Canvas,
        plot: Plot,
        cursorX: Float,
        rows: List<Pair<String, Int>>,
    ) {
        val pad = READOUT_PADDING_DP * density
        val rowHeight = textPaint.textSize + ROW_GAP_DP * density
        val boxWidth = rows.maxOf { textPaint.measureText(it.first) } + pad * HALF
        val boxHeight = rowHeight * rows.size + pad * HALF - ROW_GAP_DP * density
        val onLeft = cursorX > (plot.left + plot.right) / HALF
        val left = if (onLeft) plot.left + pad else plot.right - pad - boxWidth
        val base = if (Color.luminance(textColor) > LUMINANCE_HALF) Color.BLACK else Color.WHITE
        background.color =
            Color.argb(
                READOUT_BACKGROUND_ALPHA,
                Color.red(base),
                Color.green(base),
                Color.blue(base),
            )
        canvas.drawRect(left, plot.top, left + boxWidth, plot.top + boxHeight, background)
        textPaint.textAlign = Paint.Align.LEFT
        rows.forEachIndexed { i, (text, color) ->
            textPaint.color = color
            canvas.drawText(
                text,
                left + pad,
                plot.top + pad + rowHeight * i + textPaint.textSize,
                textPaint,
            )
        }
        textPaint.color = textColor
    }
}
