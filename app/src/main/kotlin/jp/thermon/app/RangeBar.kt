/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.app.DatePickerDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import jp.thermon.core.ChartRange
import jp.thermon.core.ChartWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ボタンの幅の重み (ボタンの数で, 均等に分ける). */
private const val BUTTON_WEIGHT = 1f

/** 近道のボタンの左右の余白 (dp). 6 個が, 狭い画面に入るように, 小さくする. */
private const val PRESET_PADDING_DP = 4

/** 1 か月ごとの月の数え方で, Android の DatePickerDialog の月 (0 から 11) にそろえる値. */
private const val MONTH_OFFSET = 1

/**
 * グラフの範囲を選ぶ部品: 近道のボタン (1 h から 1 年) と, 開始と終了の日付を選ぶボタン.
 *
 * 近道のボタンを押すと, その範囲の開始と終了の日付が, 日付のボタンに出る. 日付のボタンを押すと, カレンダーで
 * 日付を選べる (開始が終了より後になる選び方は, もう一方を同じ日にそろえる).
 *
 * @property context コンテキスト
 * @property zone 日付のタイムゾーン
 * @property onPreset 近道のボタンを押したときの処理
 * @property onDates 日付を選んだときの処理 (開始の日と終了の日)
 */
class RangeBar(
    private val context: Context,
    private val zone: ZoneId,
    private val onPreset: (ChartRange) -> Unit,
    private val onDates: (LocalDate, LocalDate) -> Unit,
) {
    /** 表示をまとめた View. */
    val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val presets: Map<ChartRange, Button> = createPresets()
    private val fromButton = Button(context)
    private val toButton = Button(context)
    private var fromDate: LocalDate = LocalDate.now(zone)
    private var toDate: LocalDate = fromDate

    init {
        container.addView(row(presets.values.toList()))
        container.addView(row(listOf(fromButton, toButton)))
        fromButton.setOnClickListener { pick(fromDate) { select(it, maxOf(it, toDate)) } }
        toButton.setOnClickListener { pick(toDate) { select(minOf(it, fromDate), it) } }
        updateDates()
    }

    /**
     * 選んでいる範囲を表示する (近道のボタンを押せなくして, 日付のボタンに, 窓の開始と終了の日付を出す).
     *
     * @param preset 選んでいる近道 (日付で選んだときは, null)
     * @param window 表示する窓
     */
    fun show(
        preset: ChartRange?,
        window: ChartWindow,
    ) {
        presets.forEach { (range, button) -> button.isEnabled = range != preset }
        fromDate = Instant.ofEpochSecond(window.fromTs).atZone(zone).toLocalDate()
        toDate = Instant.ofEpochSecond(window.toTs).atZone(zone).toLocalDate()
        updateDates()
    }

    /**
     * 日付を選んだことにして, 通知する.
     *
     * @param from 開始の日
     * @param to 終了の日
     */
    private fun select(
        from: LocalDate,
        to: LocalDate,
    ) {
        fromDate = from
        toDate = to
        updateDates()
        onDates(from, to)
    }

    /** 日付のボタンの文字を, 選んでいる日付にする. */
    private fun updateDates() {
        fromButton.text = context.getString(R.string.range_from, fromDate.toString())
        toButton.text = context.getString(R.string.range_to, toDate.toString())
    }

    /**
     * カレンダーを出して, 日付を選ばせる.
     *
     * @param initial 最初に選んでいる日
     * @param picked 日付を選んだときの処理
     */
    private fun pick(
        initial: LocalDate,
        picked: (LocalDate) -> Unit,
    ) {
        DatePickerDialog(
            context,
            { _, year, month, day -> picked(LocalDate.of(year, month + MONTH_OFFSET, day)) },
            initial.year,
            initial.monthValue - MONTH_OFFSET,
            initial.dayOfMonth,
        ).show()
    }

    /**
     * 近道のボタンを作る.
     *
     * @return 範囲ごとのボタン
     */
    private fun createPresets(): Map<ChartRange, Button> {
        val labels =
            mapOf(
                ChartRange.HOUR_1 to R.string.range_1h,
                ChartRange.HOUR_6 to R.string.range_6h,
                ChartRange.HOUR_24 to R.string.range_24h,
                ChartRange.DAY_7 to R.string.range_7d,
                ChartRange.DAY_30 to R.string.range_30d,
                ChartRange.YEAR_1 to R.string.range_1y,
            )
        return labels.mapValues { (range, labelId) ->
            Button(context).apply {
                text = context.getString(labelId)
                minWidth = 0
                minimumWidth = 0
                setPadding(
                    context.dp(PRESET_PADDING_DP),
                    paddingTop,
                    context.dp(PRESET_PADDING_DP),
                    paddingBottom,
                )
                setOnClickListener { onPreset(range) }
            }
        }
    }

    /**
     * ボタンを横に均等に並べた行を作る.
     *
     * @param buttons ボタン
     * @return 行
     */
    private fun row(buttons: List<Button>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach {
                addView(
                    it,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        BUTTON_WEIGHT,
                    ),
                )
            }
        }
}
