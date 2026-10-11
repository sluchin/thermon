/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

/** 設定の機器の区切り文字. */
private const val NODE_SEPARATOR = ","

/** 設定の, 機器の識別子と, 表示名の区切り文字. */
private const val LABEL_SEPARATOR = "="

/**
 * 表示する機器 (識別子と, 表示名).
 *
 * @property node 機器の識別子 (MAC アドレス)
 * @property label 表示名 (室外, 室内など. ないときは, null)
 */
data class NodeSpec(
    val node: String,
    val label: String?,
) {
    /** 画面に出す名前 (表示名があれば, 「室外 (ED:2E:C4:46:3B:11)」の形. なければ, 識別子). */
    val title: String get() = label?.let { "$it ($node)" } ?: node
}

/**
 * 設定の文字列 (例: ED:2E:C4:46:3B:11=室外,00:AA:01:00:00:42=室内) を, 機器の並びにする.
 *
 * 機器は, コンマで区切る. 「識別子=表示名」の形にすると, 表示名が付く (=を省くと, 表示名なし).
 * 前後の空白と, 空の項目は, 除く. 識別子が空の項目 (=室外 など) も, 除く.
 *
 * @param spec 設定の文字列
 * @return 機器の並び (設定の順)
 */
fun parseNodes(spec: String): List<NodeSpec> =
    spec
        .split(NODE_SEPARATOR)
        .mapNotNull { item ->
            val node = item.substringBefore(LABEL_SEPARATOR).trim()
            val label = item.substringAfter(LABEL_SEPARATOR, "").trim()
            if (node.isEmpty()) null else NodeSpec(node, label.ifEmpty { null })
        }
