/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [RotatingLogFile] と, ログの文字列の関数の単体テスト. */
class LogFileTest {
    private val dir: File = Files.createTempDirectory("log").toFile()

    /** 一時のディレクトリを消す. */
    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** 1 行目に日時, 重大度, タグ, 内容. 例外は, 次の行からスタックトレース. */
    @Test
    fun formatLogEntryWithAndWithoutError() {
        val time = ZonedDateTime.parse("2026-10-21T08:12:03.045Z")

        assertEquals(
            "2026-10-21 08:12:03.045 I/Readings: Loaded\n",
            formatLogEntry(time, LogLevel.INFO, "Readings", "Loaded"),
        )
        val text =
            formatLogEntry(
                time,
                LogLevel.ERROR,
                "T",
                "Failed",
                IllegalStateException("boom"),
            )
        assertTrue(
            text.startsWith(
                "2026-10-21 08:12:03.045 E/T: Failed\njava.lang.IllegalStateException: boom\n\tat ",
            ),
        )
        assertTrue(text.endsWith(")\n"))
    }

    /** 1 件ずつに分けて, 続きの行は前の件に付ける. 最初の行が続きなら, 日時のない件にする. */
    @Test
    fun parseLogJoinsContinuationLines() {
        val text =
            "  at x\n" +
                "2026-10-21 08:12:03.045 D/A: one\n" +
                "2026-10-21 08:12:04.000 E/B: two\n" +
                "\tat y\n" +
                "2026-10-21 08:12:05.000 W/C:\n"

        assertEquals(
            listOf(
                LogEntry("", LogLevel.INFO, "", "  at x"),
                LogEntry("2026-10-21 08:12:03.045", LogLevel.DEBUG, "A", "one"),
                LogEntry("2026-10-21 08:12:04.000", LogLevel.ERROR, "B", "two\n\tat y"),
                LogEntry("2026-10-21 08:12:05.000", LogLevel.WARN, "C", ""),
            ),
            parseLog(text),
        )
        assertEquals(emptyList(), parseLog(""))
    }

    /** 重大度で絞り込み, 新しい順に, 空の行で区切って出す. */
    @Test
    fun filterAndRenderLog() {
        val entries =
            listOf(
                LogEntry("", LogLevel.INFO, "", "cut"),
                LogEntry("t1", LogLevel.DEBUG, "A", "1"),
                LogEntry("t2", LogLevel.WARN, "B", "2"),
                LogEntry("t3", LogLevel.ERROR, "C", "3"),
            )

        assertEquals(listOf("2", "3"), filterLog(entries, LogLevel.WARN).map { it.message })
        assertEquals(4, filterLog(entries, LogLevel.DEBUG).size)
        assertEquals("t3 E/C\n3\n\nt2 W/B\n2\n\nt1 D/A\n1\n\ncut", renderLog(entries))
    }

    /** 上限を超える前に切り替えて, 古いファイルは決めた数だけ残す. */
    @Test
    fun appendRotatesAndKeepsBackups() {
        val log = RotatingLogFile(File(dir, "logs/app.log"), maxBytes = 4, maxBackups = 2)

        log.append("aa\n")
        log.append("bb\n")
        log.append("cc\n")
        log.append("dd\n")

        assertEquals("dd\n", log.file.readText())
        assertEquals("cc\n", log.backup(1).readText())
        assertEquals("bb\n", log.backup(2).readText())
        assertFalse(log.backup(3).exists())
        assertEquals("bb\ncc\ndd\n", log.readTail(100))
    }

    /** 古いファイルを残さないなら, 切り替えるときに消す. 空のファイルには, 上限を超えても書く. */
    @Test
    fun appendWithoutBackups() {
        val log = RotatingLogFile(File(dir, "app.log"), maxBytes = 2, maxBackups = 0)

        log.append("long\n")
        assertEquals("long\n", log.file.readText())
        log.append("x\n")
        assertEquals("x\n", log.file.readText())
        log.clear()
        assertFalse(log.file.exists())
    }

    /** 上限までは, 同じファイルに書き足す. */
    @Test
    fun appendWithinTheLimit() {
        val log = RotatingLogFile(File(dir, "app.log"), maxBytes = 100, maxBackups = 1)

        log.append("a\n")
        log.append("b\n")

        assertEquals("a\nb\n", log.file.readText())
        assertFalse(log.backup(1).exists())
    }

    /** 上限を超える分は古い方から捨て, 切れた最初の行も捨てる. 上限で止めたら, 古いファイルは読まない. */
    @Test
    fun readTailDropsTheCutLine() {
        val log = RotatingLogFile(File(dir, "app.log"), maxBytes = 100, maxBackups = 2)
        log.backup(2).writeText("old\n")
        log.backup(1).writeText("line1\nb\n")
        log.file.writeText("c\n")

        assertEquals("b\nc\n", log.readTail(5))
        assertEquals("c\n", log.readTail(2))
        assertEquals("", log.readTail(1))
        assertEquals("old\nline1\nb\nc\n", log.readTail(100))
    }

    /** ファイルがなければ, 空. 消すと, 古いファイルも消える. */
    @Test
    fun readTailAndClear() {
        val log = RotatingLogFile(File(dir, "app.log"), maxBytes = 100, maxBackups = 2)
        assertEquals("", log.readTail(100))

        log.append("a\n")
        log.backup(1).writeText("b\n")
        log.clear()

        assertFalse(log.file.exists())
        assertFalse(log.backup(1).exists())
        assertEquals("", log.readTail(100))
    }
}
