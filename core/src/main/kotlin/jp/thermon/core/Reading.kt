/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

/**
 * DynamoDB のテーブル thermo-readings の 1 件 (温度と湿度などの測定値).
 *
 * @property node 機器の識別子 (MAC アドレス. パーティションキー)
 * @property ts 受信した時刻 (UNIX 時刻 (s). ソートキー)
 * @property type 機器の種類 (switchbot または thermo. ないこともある)
 * @property temperatureC 温度 (℃)
 * @property humidity 湿度 (%) (測れないセンサでは, null)
 * @property battery 電池残量 (%) (SwitchBot だけ. わからないときは, null)
 */
data class Reading(
    val node: String,
    val ts: Long,
    val type: String?,
    val temperatureC: Double,
    val humidity: Double?,
    val battery: Double?,
)
