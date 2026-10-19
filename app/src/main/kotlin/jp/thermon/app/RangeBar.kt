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
import java.time.LocalDate
import java.time.ZoneId

/** ボタンの幅の重み (ボタンの数で, 均等に分ける). */
private const val BUTTON_WEIGHT = 1f

/** 近道のボタンの左右の余白 (dp). 6 個が, 狭い画面に入るように, 小さくする. */
private const val PRESET_PADDING_DP = 4

/** 1 か月ごとの月の数え方で, Android の DatePickerDialog の月 (0 から 11) にそろえる値. */
private const val MONTH_OFFSET = 1

/**
 * グラフの範囲を選ぶ部品: 近道のボタン (1 h から 1 年) と, 開始と終了の日付を選ぶボタンと, 日付を消すボタン.
 *
 * 日付は, 最初は選んでいない (近道の範囲で表示する). 日付のボタンを押すと, カレンダーで日付を選べる
 * (開始が終了より後になる選び方は, もう一方を同じ日にそろえる). 片方だけを選んだときは, 開始がなければ
 * 終了の日だけ, 終了がなければ今日までにする. 近道のボタンか, 消すボタンを押すと, 日付を選んでいない状態に戻る.
 *
 * @property context コンテキスト
 * @property zone 日付のタイムゾーン
 * @property onPreset 近道のボタンを押したときの処理
 * @property onDates 日付を選んだときの処理 (開始の日と終了の日)
 * @property onClear 日付を消したときの処理
 */
class RangeBar(
    private val context: Context,
    private val zone: ZoneId,
    private val onPreset: (ChartRange) -> Unit,
    private val onDates: (LocalDate, LocalDate) -> Unit,
    private val onClear: () -> Unit,
) {
    /** 表示をまとめた View. */
    val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val presets: Map<ChartRange, Button> = createPresets()
    private val fromButton = Button(context)
    private val toButton = Button(context)
    private val clearButton =
        Button(context).apply {
            text = context.getString(R.string.range_clear)
            setOnClickListener { clear() }
        }

    /** 選んだ開始の日 (選んでいないときは, null). */
    private var fromDate: LocalDate? = null

    /** 選んだ終了の日 (選んでいないときは, null). */
    private var toDate: LocalDate? = null

    init {
        container.addView(row(presets.values.toList()))
        container.addView(
            row(listOf(fromButton, toButton)).apply {
                // 消すボタンは, 文字の幅だけにして, 日付のボタンを広くする
                addView(
                    clearButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
        fromButton.setOnClickListener {
            pick(fromDate) { from -> select(from, toDate?.let { maxOf(it, from) }) }
        }
        toButton.setOnClickListener {
            pick(toDate) { to -> select(fromDate?.let { minOf(it, to) }, to) }
        }
        updateDates()
    }

    /**
     * 選んでいる近道を表示する (選んでいる近道のボタンを押せなくする). 近道のときは, 日付を消す.
     *
     * @param preset 選んでいる近道 (日付で選んだときは, null)
     */
    fun show(preset: ChartRange?) {
        presets.forEach { (range, button) -> button.isEnabled = range != preset }
        if (preset != null) {
            fromDate = null
            toDate = null
            updateDates()
        }
    }

    /**
     * 日付を選んだことにして, 通知する (選んでいない方は, 開始なら終了の日, 終了なら今日にする).
     *
     * @param from 開始の日 (選んでいないときは, null)
     * @param to 終了の日 (選んでいないときは, null)
     */
    private fun select(
        from: LocalDate?,
        to: LocalDate?,
    ) {
        fromDate = from
        toDate = to
        updateDates()
        val last = to ?: LocalDate.now(zone)
        onDates(from ?: last, last)
    }

    /** 選んだ日付を消して, 通知する. */
    private fun clear() {
        fromDate = null
        toDate = null
        updateDates()
        onClear()
    }

    /**
     * 日付のボタンの文字を, 選んでいる日付にする (選んでいないときは, 未設定の印).
     * 消すボタンは, 日付を選んでいるときだけ押せる.
     */
    private fun updateDates() {
        val unset = context.getString(R.string.range_unset)
        fromButton.text = context.getString(R.string.range_from, fromDate?.toString() ?: unset)
        toButton.text = context.getString(R.string.range_to, toDate?.toString() ?: unset)
        clearButton.isEnabled = fromDate != null || toDate != null
    }

    /**
     * カレンダーを出して, 日付を選ばせる.
     *
     * @param selected 選んでいる日 (選んでいないときは, null. カレンダーは, 今日から始める)
     * @param picked 日付を選んだときの処理
     */
    private fun pick(
        selected: LocalDate?,
        picked: (LocalDate) -> Unit,
    ) {
        val initial = selected ?: LocalDate.now(zone)
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
