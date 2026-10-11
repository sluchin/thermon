/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** [parseNodes] と [NodeSpec] の単体テスト. */
class NodeSpecTest {
    /** 識別子だけの機器は, 表示名なし (画面の名前は, 識別子). */
    @Test
    fun parseNodesWithoutLabel() {
        val nodes = parseNodes("AA:BB:CC:DD:EE:01,AA:BB:CC:DD:EE:02")

        assertEquals(
            listOf(NodeSpec("AA:BB:CC:DD:EE:01", null), NodeSpec("AA:BB:CC:DD:EE:02", null)),
            nodes,
        )
        assertEquals("AA:BB:CC:DD:EE:01", nodes[0].title)
    }

    /** 「識別子=表示名」は, 表示名が付き, 画面の名前は「表示名 (識別子)」になる. */
    @Test
    fun parseNodesWithLabel() {
        val nodes = parseNodes("ED:2E:C4:46:3B:11=室外,00:AA:01:00:00:42=室内")

        assertEquals(
            listOf(NodeSpec("ED:2E:C4:46:3B:11", "室外"), NodeSpec("00:AA:01:00:00:42", "室内")),
            nodes,
        )
        assertEquals("室外 (ED:2E:C4:46:3B:11)", nodes[0].title)
    }

    /** 前後の空白と, 空の項目と, 識別子のない項目は, 除く. 表示名が空なら, 表示名なし. */
    @Test
    fun parseNodesSkipsBlankItems() {
        val nodes = parseNodes(" AA:01 = 室外 ,, =室内,AA:02=, ")

        assertEquals(listOf(NodeSpec("AA:01", "室外"), NodeSpec("AA:02", null)), nodes)
    }

    /** 空の文字列は, 機器なし. */
    @Test
    fun parseNodesEmpty() {
        assertEquals(emptyList(), parseNodes(""))
    }
}
