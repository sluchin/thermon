/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import jp.thermon.core.ChartRange
import jp.thermon.core.ChartWindow
import jp.thermon.core.NodeSnapshot
import jp.thermon.core.ReadingStore
import jp.thermon.core.ReadingsRepository
import jp.thermon.core.customWindow
import jp.thermon.core.loadSnapshot
import jp.thermon.core.parseNodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** ログのタグ. */
private const val TAG = "Readings"

/** 画面の端と, 内容の間の余白 (dp). */
private const val CONTENT_PADDING_DP = 16

/** ミリ秒を, 秒にするための値. */
private const val MILLIS_PER_SECOND = 1000L

/** 起動したときと, 日付を消したときの範囲. */
private val DEFAULT_RANGE = ChartRange.HOUR_6

/**
 * 温度と湿度の 2 つのグラフに, 機器 (室内, 室外など) を選んで重ねて表示する画面.
 *
 * 機器は, local.properties の thermon.nodes (コンマ区切りの MAC アドレス. 「MAC アドレス=室外」のように
 * 表示名を付けられる) で指定する.
 * グラフの範囲 (1 時間, 6 時間, 24 時間) は, 上のボタンで選ぶ. 機器は, グラフの上のボタンで選ぶ.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var root: ScrollView
    private var panel: ChartsPanel? = null
    private var nodes: List<String> = emptyList()
    private var rangeBar: RangeBar? = null
    private var repository: ReadingsRepository? = null
    private var store: ReadingStore? = null
    private var preset: ChartRange? = DEFAULT_RANGE
    private var dates: Pair<LocalDate, LocalDate>? = null
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

        requestNotificationPermission()
        val specs = parseNodes(BuildConfig.NODES)
        if (BuildConfig.IDENTITY_POOL_ID.isEmpty() || specs.isEmpty()) {
            content.addView(TextView(this).apply { text = getString(R.string.not_configured) })
            return
        }
        content.addView(createRangeBar().container)
        nodes = specs.map { it.node }
        panel = ChartsPanel(this, specs).also { content.addView(it.container) }
        repository = AwsClients.readings()
        store = SqliteReadingStore.get(this)
        OutdoorAlertWorker.schedule(this)
        reload()
    }

    /** 室外の通知のために, 通知の許可を求める (Android 13 以降. 許可済みなら, 何も出ない). */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    /**
     * 範囲を選ぶ部品 (近道のボタンと, 開始と終了の日付) を作る.
     *
     * @return 範囲を選ぶ部品
     */
    private fun createRangeBar(): RangeBar =
        RangeBar(
            this,
            ZoneId.systemDefault(),
            onPreset = { selected ->
                preset = selected
                dates = null
                reload()
            },
            onDates = { from, to ->
                preset = null
                dates = from to to
                reload()
            },
            onClear = {
                preset = DEFAULT_RANGE
                dates = null
                reload()
            },
        ).also { rangeBar = it }

    /**
     * 選んでいる範囲の窓を作る (近道なら, 現在までの範囲. 日付なら, その日の 0 時から).
     *
     * @param nowTs 現在の時刻 (UNIX 時刻 (s))
     * @return 窓
     */
    private fun currentWindow(nowTs: Long): ChartWindow {
        val range = preset
        val picked = dates
        return when {
            range != null -> range.window(nowTs)
            picked != null ->
                customWindow(
                    picked.first,
                    picked.second,
                    ZoneId.systemDefault(),
                    nowTs,
                )
            else -> DEFAULT_RANGE.window(nowTs)
        }
    }

    /**
     * 機器ごとに, 測定値を取り出して, 表示する (取り出し中の処理があれば, 止める).
     *
     * 取り出しに失敗した機器は, エラーのメッセージを表示する (ほかの機器は, 続ける).
     * 機器が 1 台取り出せるたびに, 表示を更新する.
     */
    private fun reload() {
        val source = repository
        val saved = store
        val view = panel
        if (source != null && saved != null && view != null) {
            val nowTs = System.currentTimeMillis() / MILLIS_PER_SECOND
            val window = currentWindow(nowTs)
            rangeBar?.show(preset)
            loadJob?.cancel()
            view.showLoading()
            loadJob =
                scope.launch {
                    val loaded = mutableMapOf<String, Result<NodeSnapshot>>()
                    for (node in nodes) {
                        loaded[node] =
                            runCatching { loadSnapshot(source, saved, node, window, nowTs) }
                                .onSuccess { AppLog.d(TAG) { "Loaded the readings of $node" } }
                                .onFailure {
                                    AppLog.w(
                                        TAG,
                                        "Could not load the readings of $node",
                                        it,
                                    )
                                }
                        // 範囲を選び直して, 止められたときは, 古い結果を表示しない
                        ensureActive()
                        val shown = loaded.toMap()
                        runOnUiThread { view.show(shown, ZoneId.systemDefault()) }
                    }
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
