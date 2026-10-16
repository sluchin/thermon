/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

/** 室外の機器の表示名 (thermon.nodes の「MAC アドレス=室外」). */
private const val OUTDOOR_LABEL = "室外"

/** 凍る温度 (℃). これを下回ると通知する. */
const val FREEZING_BELOW_C = 0.0

/** 湿度が高い値 (%). これを超えると通知する. */
const val HUMID_ABOVE = 80.0

/** 測定値を古いとみなす経過時間 (s). 古い測定値では, 判定しない. */
private const val STALE_SECONDS = 3600L

/** 室外で通知する状態. */
enum class OutdoorAlert {
    /** 温度が [FREEZING_BELOW_C] を下回った. */
    FREEZING,

    /** 湿度が [HUMID_ABOVE] を超えた. */
    HUMID,
}

/**
 * 機器の中から, 室外の機器 (表示名が「室外」) を探す.
 *
 * @param nodes 機器の並び
 * @return 室外の機器. ないときは, null
 */
fun findOutdoor(nodes: List<NodeSpec>): NodeSpec? = nodes.firstOrNull { it.label == OUTDOOR_LABEL }

/**
 * 測定値が, 通知する状態かを調べる.
 *
 * 温度が [FREEZING_BELOW_C] 未満なら [OutdoorAlert.FREEZING], 湿度が [HUMID_ABOVE] を超えていれば
 * [OutdoorAlert.HUMID] (ちょうどの値は, 通知しない). 湿度がない測定値は, 湿度を判定しない.
 * 測定が止まっているとき (古い測定値) は, 状態が分からないので, null を返す.
 *
 * @param reading 最新の測定値
 * @param nowTs 現在の時刻 (UNIX 時刻 (s))
 * @return 通知する状態 (なければ, 空). 測定値が古いときは, null
 */
fun detectAlerts(
    reading: Reading,
    nowTs: Long,
): Set<OutdoorAlert>? =
    if (nowTs - reading.ts > STALE_SECONDS) {
        null
    } else {
        setOfNotNull(
            OutdoorAlert.FREEZING.takeIf { reading.temperatureC < FREEZING_BELOW_C },
            OutdoorAlert.HUMID.takeIf { (reading.humidity ?: 0.0) > HUMID_ABOVE },
        )
    }

/**
 * 今の状態のうち, まだ通知していないものを求める.
 *
 * 通知したあと, その状態が続く間は, 通知しない. 状態が解消してから, また起きたときは, 通知する
 * (呼び出し元が, 今の状態を「通知した状態」として保存し直す).
 *
 * @param current 今の状態
 * @param notified 通知した状態
 * @return 新しく通知する状態
 */
fun newAlerts(
    current: Set<OutdoorAlert>,
    notified: Set<OutdoorAlert>,
): Set<OutdoorAlert> = current - notified
