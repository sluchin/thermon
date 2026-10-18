/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import jp.thermon.core.OutdoorAlert
import jp.thermon.core.Reading
import jp.thermon.core.detectAlerts
import jp.thermon.core.findOutdoor
import jp.thermon.core.formatChartValue
import jp.thermon.core.newAlerts
import jp.thermon.core.parseNodes
import jp.thermon.core.syncReadings
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** ログのタグ. */
private const val TAG = "Alert"

/** 定期の確認の名前. */
private const val WORK_NAME = "outdoor_alert"

/** 確認の間隔 (分). WorkManager の定期の仕事の最小値. */
private const val INTERVAL_MINUTES = 15L

/** 通知のチャンネルの ID. */
private const val CHANNEL_ID = "outdoor_alert"

/** 通知した状態を覚える, 設定のファイルの名前. */
private const val PREFS = "outdoor_alert"

/** 通知した状態 (OutdoorAlert の名前の集合) の, 設定のキー. */
private const val KEY_NOTIFIED = "notified"

/** ミリ秒を, 秒にするための値. */
private const val MILLIS_PER_SECOND = 1000L

/**
 * 室外の最新の測定値を調べて, 温度が 0 ℃ を下回った, または, 湿度が 80 % を超えたときに通知する, 定期の仕事.
 * 全ての機器の新しい測定値を, 端末に保存する (変化があったものと, 1 時間に 1 点).
 *
 * 状態が続く間は, 繰り返し通知しない (解消してから, また起きたときに通知する). 通知の権限がないときは,
 * 通知せず, 通知したことにもしない. AWS から取り出せなかったときは, 失敗にして, 次の回に任せる.
 *
 * @param context コンテキスト
 * @param params 仕事の引数
 */
class OutdoorAlertWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val specs = parseNodes(BuildConfig.NODES)
        val outdoor = findOutdoor(specs)
        val reading = outdoor?.let { runBlocking { AwsClients.readings().latest(it.node) } }
        val current =
            reading?.let { detectAlerts(it, System.currentTimeMillis() / MILLIS_PER_SECOND) }
        if (reading != null && current != null && canNotify(applicationContext)) {
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
            val fresh =
                newAlerts(current, OutdoorAlert.entries.filter { it.name in notified }.toSet())
            AppLog.d(TAG) { "Outdoor alerts: $current" }
            if (fresh.isNotEmpty()) {
                AppLog.i(TAG, "Notifying the new outdoor alerts: $fresh")
                notify(applicationContext, fresh, reading)
            }
            prefs.edit().putStringSet(KEY_NOTIFIED, current.map { it.name }.toSet()).apply()
        }
        saveReadings(specs.map { it.node })
        AppLog.i(TAG, "Saved the readings of ${specs.size} nodes")
        return Result.success()
    }

    /**
     * 全ての機器の, AWS の新しい測定値を, 端末に保存する (DynamoDB は 30 日しか残さないので, 開かなくても保存する).
     *
     * 取り出せなかったときは, 例外をそのまま伝えて, この回を失敗にする (次の回に任せる).
     *
     * @param nodes 機器の識別子
     */
    private fun saveReadings(nodes: List<String>) {
        if (nodes.isEmpty()) {
            return
        }
        val remote = AwsClients.readings()
        val store = SqliteReadingStore.get(applicationContext)
        val nowTs = System.currentTimeMillis() / MILLIS_PER_SECOND
        runBlocking { nodes.forEach { syncReadings(remote, store, it, nowTs) } }
    }

    /** 通知の作成と, 定期の確認の予約. */
    companion object {
        /**
         * 通知できるかを調べる (Android 13 以降の通知の権限も, 通知を止めた設定も含む).
         *
         * @param context コンテキスト
         * @return 通知できれば, true
         */
        private fun canNotify(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

        /**
         * 新しい状態を通知する (状態ごとに 1 件. 同じ状態の通知は, 置き換える).
         *
         * @param context コンテキスト
         * @param alerts 新しい状態
         * @param reading 最新の測定値
         */
        private fun notify(
            context: Context,
            alerts: Set<OutdoorAlert>,
            reading: Reading,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notify_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            for (alert in alerts) {
                val text =
                    when (alert) {
                        OutdoorAlert.FREEZING ->
                            context.getString(
                                R.string.notify_freezing,
                                formatChartValue(reading.temperatureC),
                            )
                        OutdoorAlert.HUMID ->
                            context.getString(
                                R.string.notify_humid,
                                formatChartValue(reading.humidity ?: 0.0),
                            )
                    }
                val notification =
                    Notification
                        .Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(R.string.notify_title))
                        .setContentText(text)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                manager.notify(alert.ordinal, notification)
            }
        }

        /**
         * 定期の確認を予約する (すでに予約してあれば, そのままにする).
         *
         * @param context コンテキスト
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<OutdoorAlertWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
