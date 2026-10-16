/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import jp.thermon.core.Reading
import jp.thermon.core.ReadingStore

/** データベースのファイル名. */
private const val DB_NAME = "readings.db"

/** データベースの版. */
private const val DB_VERSION = 1

/** テーブル名. */
private const val TABLE = "readings"

/** 列名: 機器の識別子. */
private const val COL_NODE = "node"

/** 列名: 時刻 (UNIX 時刻 (s)). */
private const val COL_TS = "ts"

/** 列名: 機器の種類. */
private const val COL_TYPE = "type"

/** 列名: 温度 (℃). */
private const val COL_TEMPERATURE = "temperature_c"

/** 列名: 湿度 (%). */
private const val COL_HUMIDITY = "humidity"

/** 列名: 電池残量 (%). */
private const val COL_BATTERY = "battery"

/** 列の位置: [COL_NODE]. */
private const val IDX_NODE = 0

/** 列の位置: [COL_TS]. */
private const val IDX_TS = 1

/** 列の位置: [COL_TYPE]. */
private const val IDX_TYPE = 2

/** 列の位置: [COL_TEMPERATURE]. */
private const val IDX_TEMPERATURE = 3

/** 列の位置: [COL_HUMIDITY]. */
private const val IDX_HUMIDITY = 4

/** 列の位置: [COL_BATTERY]. */
private const val IDX_BATTERY = 5

/**
 * 端末の SQLite に, 測定値を保存する ([ReadingStore]). アプリのデータを消さない限り, 残る.
 *
 * 主キーは, 機器と時刻 (同じ測定値の二重の保存は, 無視する). 画面の取り出しと, 定期の仕事から,
 * 別のスレッドで使う (SQLite の排他制御に任せる).
 *
 * @param context コンテキスト
 */
class SqliteReadingStore private constructor(
    context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION),
    ReadingStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_NODE TEXT NOT NULL, $COL_TS INTEGER NOT NULL, $COL_TYPE TEXT, " +
                "$COL_TEMPERATURE REAL NOT NULL, $COL_HUMIDITY REAL, $COL_BATTERY REAL, " +
                "PRIMARY KEY ($COL_NODE, $COL_TS))",
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // 版 1 だけ. 版が上がったときに, 移行を書く
    }

    override fun append(readings: List<Reading>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            readings.forEach {
                db.insertWithOnConflict(TABLE, null, toValues(it), SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun range(
        node: String,
        fromTs: Long,
        toTs: Long,
    ): List<Reading> =
        readableDatabase
            .query(
                TABLE,
                COLUMNS,
                "$COL_NODE = ? AND $COL_TS BETWEEN ? AND ?",
                arrayOf(node, fromTs.toString(), toTs.toString()),
                null,
                null,
                "$COL_TS ASC",
            ).use { cursor ->
                generateSequence { cursor.takeIf { it.moveToNext() } }.map(::toReading).toList()
            }

    override fun latest(node: String): Reading? =
        readableDatabase
            .query(TABLE, COLUMNS, "$COL_NODE = ?", arrayOf(node), null, null, "$COL_TS DESC", "1")
            .use { cursor -> if (cursor.moveToFirst()) toReading(cursor) else null }

    /** 唯一のインスタンスの置き場. */
    companion object {
        /** 取り出す列 (列の位置の定数と, 同じ順). */
        private val COLUMNS =
            arrayOf(COL_NODE, COL_TS, COL_TYPE, COL_TEMPERATURE, COL_HUMIDITY, COL_BATTERY)

        @Volatile private var instance: SqliteReadingStore? = null

        /**
         * 保存先を取り出す (1 つのデータベースを, 画面と定期の仕事で共有する).
         *
         * @param context コンテキスト
         * @return 保存先
         */
        fun get(context: Context): SqliteReadingStore =
            instance ?: synchronized(this) {
                instance ?: SqliteReadingStore(context.applicationContext).also { instance = it }
            }

        /**
         * 測定値を, 保存する列の値にする.
         *
         * @param reading 測定値
         * @return 列の値
         */
        private fun toValues(reading: Reading): ContentValues =
            ContentValues().apply {
                put(COL_NODE, reading.node)
                put(COL_TS, reading.ts)
                put(COL_TYPE, reading.type)
                put(COL_TEMPERATURE, reading.temperatureC)
                put(COL_HUMIDITY, reading.humidity)
                put(COL_BATTERY, reading.battery)
            }

        /**
         * カーソルの今の行を, 測定値にする.
         *
         * @param cursor カーソル ([COLUMNS] の順)
         * @return 測定値
         */
        private fun toReading(cursor: Cursor): Reading =
            Reading(
                node = cursor.getString(IDX_NODE),
                ts = cursor.getLong(IDX_TS),
                type = cursor.getString(IDX_TYPE),
                temperatureC = cursor.getDouble(IDX_TEMPERATURE),
                humidity =
                    if (cursor.isNull(
                            IDX_HUMIDITY,
                        )
                    ) {
                        null
                    } else {
                        cursor.getDouble(IDX_HUMIDITY)
                    },
                battery =
                    if (cursor.isNull(
                            IDX_BATTERY,
                        )
                    ) {
                        null
                    } else {
                        cursor.getDouble(IDX_BATTERY)
                    },
            )
    }
}
