/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** ログの日時の形 (端末の時刻. ミリ秒まで). */
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** ログの 1 件の, 最初の行 (`日時 重大度/タグ: 内容`). */
private val ENTRY_HEAD =
    Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) ([DIWE])/([^:]*:.*)$""")

/**
 * ログの重大度 (低い順).
 *
 * @property letter ログファイルに書く 1 文字 (logcat と同じ)
 */
enum class LogLevel(
    val letter: Char,
) {
    /** 詳細なデバッグ情報 (デバッグ版だけ). */
    DEBUG('D'),

    /** 最低限の情報. */
    INFO('I'),

    /** 失敗したが, 続けられる. */
    WARN('W'),

    /** 利用者の操作が, 失敗した. */
    ERROR('E'),
}

/**
 * ログファイルの 1 件.
 *
 * @property time 日時 (端末の時刻. 形の分からない行は空)
 * @property level 重大度
 * @property tag タグ (機能の名前)
 * @property message 内容 (スタックトレースなど, 複数の行のこともある)
 */
data class LogEntry(
    val time: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * ログの 1 件を, ログファイルに書く文字列にする (`2026-10-21 08:12:03.123 I/Readings: 内容`).
 *
 * 例外があれば, 次の行から, スタックトレースを書く. 最後に改行を付ける.
 *
 * @param time 日時 (端末のタイムゾーン)
 * @param level 重大度
 * @param tag タグ
 * @param message 内容
 * @param error 例外 (なければ, null)
 * @return ログファイルに書く文字列
 */
fun formatLogEntry(
    time: ZonedDateTime,
    level: LogLevel,
    tag: String,
    message: String,
    error: Throwable? = null,
): String {
    val head = "${TIME_FORMAT.format(time)} ${level.letter}/$tag: $message"
    val trace = error?.let { "\n" + it.stackTraceToString().trimEnd() } ?: ""
    return "$head$trace\n"
}

/**
 * ログファイルの文字列を, 1 件ずつに分ける. 形の合わない行は, 前の件の続き (スタックトレースなど) にする.
 *
 * @param text ログファイルの文字列
 * @return ログ (古い順)
 */
fun parseLog(text: String): List<LogEntry> {
    val entries = mutableListOf<LogEntry>()
    for (line in text.lines()) {
        val head = ENTRY_HEAD.find(line)
        if (head != null) {
            // 3 つ目は「タグ: 内容」
            val (time, letter, rest) = head.destructured
            val level = LogLevel.entries.first { it.letter == letter.single() }
            entries +=
                LogEntry(
                    time,
                    level,
                    rest.substringBefore(':'),
                    rest.substringAfter(':').removePrefix(" "),
                )
        } else if (entries.isNotEmpty()) {
            val last = entries.removeAt(entries.lastIndex)
            entries += last.copy(message = last.message + "\n" + line)
        } else if (line.isNotEmpty()) {
            // ファイルの最初が, 前の件の続きのとき (読む量の上限で, 切れたとき)
            entries += LogEntry("", LogLevel.INFO, "", line)
        }
    }
    // 最後の改行で付いた, 空の行を除く
    return entries.map { it.copy(message = it.message.trimEnd('\n')) }
}

/**
 * 重大度で, ログを絞り込む.
 *
 * @param entries ログ
 * @param minimum 出す, 最も低い重大度
 * @return 絞り込んだログ (順は変えない)
 */
fun filterLog(
    entries: List<LogEntry>,
    minimum: LogLevel,
): List<LogEntry> = entries.filter { it.level >= minimum }

/**
 * ログを, 画面に出す文字列にする (新しい順. 1 件ごとに, 空の行で区切る).
 *
 * @param entries ログ (古い順)
 * @return 画面に出す文字列
 */
fun renderLog(entries: List<LogEntry>): String =
    entries.asReversed().joinToString("\n\n") { entry ->
        // 日時のない件 (前の件の続き) は, 内容だけにする
        if (entry.time.isEmpty()) {
            entry.message
        } else {
            "${entry.time} ${entry.level.letter}/${entry.tag}\n${entry.message}"
        }
    }

/**
 * 大きさが上限を超えたら, 次のファイルに切り替えて書く, ログファイル.
 *
 * 切り替えたら, 今のファイルを `<名前>.1` にし, それまでの `.1` を `.2` に, というように送る.
 * 古いファイルは, [maxBackups] 個まで残す. 複数のスレッドから呼べる (どれも, 同期する).
 *
 * @property file ログファイル (切り替えても, 変わらない)
 * @property maxBytes 1 つのファイルの上限 (バイト)
 * @property maxBackups 残す, 古いファイルの数
 */
class RotatingLogFile(
    val file: File,
    private val maxBytes: Long,
    private val maxBackups: Int,
) {
    /**
     * 古いファイルのパスを返す.
     *
     * @param generation 世代 (1 が最も新しい)
     * @return `<ログファイル>.<世代>`
     */
    fun backup(generation: Int): File = File(file.path + ".$generation")

    /**
     * ログを書き足す. 書くと上限を超えるなら, 先に次のファイルに切り替える.
     *
     * @param text 書く文字列 (UTF-8)
     * @throws java.io.IOException 書けないとき
     */
    @Synchronized
    fun append(text: String) {
        val bytes = text.toByteArray()
        file.absoluteFile.parentFile.mkdirs()
        if (file.length() > 0 && file.length() + bytes.size > maxBytes) {
            rotate()
        }
        file.appendBytes(bytes)
    }

    /** 今のファイルを `.1` にし, 古いファイルを 1 つずつ送る (最も古いものは消す). */
    private fun rotate() {
        backup(maxBackups).delete()
        for (generation in maxBackups - 1 downTo 1) {
            backup(generation).renameTo(backup(generation + 1))
        }
        if (maxBackups > 0) {
            file.renameTo(backup(1))
        } else {
            file.delete()
        }
    }

    /**
     * ログの末尾を返す. 古いファイルも, 古いものから順に, つなげて読む.
     *
     * 上限を超える分は, 古い方から捨てる. 途中で切った最初の行は, 行の途中からになるので, 捨てる.
     *
     * @param limit 返す最大のバイト数
     * @return ログの文字列 (ファイルがなければ, 空)
     * @throws java.io.IOException 読めないとき
     */
    @Synchronized
    fun readTail(limit: Long): String {
        val chunks = mutableListOf<ByteArray>()
        var remaining = limit
        var truncated = false
        val files = listOf(file) + (1..maxBackups).map { backup(it) }
        for (source in files.filter { it.isFile }) {
            if (remaining == 0L) {
                break
            }
            // 1 つのファイルは, 上限 (maxBytes) までの大きさなので, 全部読んでから, 末尾を取る
            val bytes = source.readBytes()
            val start = maxOf(0L, bytes.size - remaining).toInt()
            // 途中から読むのは, 上限に達した, 最後に読むファイルだけ
            truncated = start > 0
            val chunk = bytes.copyOfRange(start, bytes.size)
            remaining -= chunk.size
            chunks += chunk
        }
        var text = chunks.asReversed().joinToString("") { it.decodeToString() }
        if (truncated) {
            text = text.substringAfter('\n', "")
        }
        return text
    }

    /** ログを消す (古いファイルも消す). */
    @Synchronized
    fun clear() {
        file.delete()
        for (generation in 1..maxBackups) {
            backup(generation).delete()
        }
    }
}
