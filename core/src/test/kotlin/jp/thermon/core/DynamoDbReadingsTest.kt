/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 試験のテーブル名. */
private const val TABLE = "thermo-readings"

/** 試験の機器の識別子. */
private const val NODE = "AA:BB:CC:DD:EE:01"

/** [DynamoDbReadings] の単体テスト (DynamoDB のクライアントは, 偽物). */
class DynamoDbReadingsTest {
    private val client = mockk<DynamoDbClient>()
    private val repository = DynamoDbReadings(client, TABLE)
    private val requests = mutableListOf<QueryRequest>()

    /** DynamoDB の 1 件を作る (null の属性は, 入れない). */
    private fun item(
        ts: Long,
        temperature: String? = "21.4",
        humidity: String? = "57",
        battery: String? = "100",
        type: String? = "switchbot",
    ): Map<String, AttributeValue> =
        buildMap {
            put("node", AttributeValue.S(NODE))
            put("ts", AttributeValue.N(ts.toString()))
            temperature?.let { put("temperature_c", AttributeValue.N(it)) }
            humidity?.let { put("humidity", AttributeValue.N(it)) }
            battery?.let { put("battery", AttributeValue.N(it)) }
            type?.let { put("type", AttributeValue.S(it)) }
        }

    /** Query の応答を, 順に返すようにして, 渡された要求を, [requests] に残す. */
    private fun givenResponses(vararg responses: QueryResponse) {
        coEvery { client.query(capture(requests)) } returnsMany responses.toList()
    }

    /** 範囲の指定で, 古い順の測定値を返す. */
    @Test
    fun rangeReturnsReadings() =
        runTest {
            givenResponses(
                QueryResponse {
                    items =
                        listOf(item(100), item(110, humidity = null, battery = null))
                },
            )

            val readings = repository.range(NODE, 100, 200)

            assertEquals(
                listOf(
                    Reading(NODE, 100, "switchbot", 21.4, 57.0, 100.0),
                    Reading(NODE, 110, "switchbot", 21.4, null, null),
                ),
                readings,
            )
        }

    /** 要求は, テーブル, キーの条件 (機器と時刻の範囲) を含む. */
    @Test
    fun rangeSendsKeyCondition() =
        runTest {
            givenResponses(QueryResponse { items = emptyList() })

            repository.range(NODE, 100, 200)

            val request = requests.single()
            assertEquals(TABLE, request.tableName)
            assertEquals("#n = :n AND #t BETWEEN :a AND :b", request.keyConditionExpression)
            assertEquals(mapOf("#n" to "node", "#t" to "ts"), request.expressionAttributeNames)
            assertEquals(
                mapOf(
                    ":n" to AttributeValue.S(NODE),
                    ":a" to AttributeValue.N("100"),
                    ":b" to AttributeValue.N("200"),
                ),
                request.expressionAttributeValues,
            )
            assertNull(request.exclusiveStartKey)
        }

    /** 結果が 1 回に収まらないとき (lastEvaluatedKey がある) は, 続きを取り出して, つなげる. */
    @Test
    fun rangeFollowsPagination() =
        runTest {
            val lastKey = mapOf("node" to AttributeValue.S(NODE), "ts" to AttributeValue.N("110"))
            givenResponses(
                QueryResponse {
                    items = listOf(item(100), item(110))
                    lastEvaluatedKey = lastKey
                },
                QueryResponse { items = listOf(item(120)) },
            )

            val readings = repository.range(NODE, 100, 200)

            assertEquals(listOf(100L, 110L, 120L), readings.map { it.ts })
            assertEquals(2, requests.size)
            assertNull(requests[0].exclusiveStartKey)
            assertEquals(lastKey, requests[1].exclusiveStartKey)
        }

    /** 読めない項目 (温度がない, 数値でない, 時刻が数値でない, 型が違う) は, 除く. */
    @Test
    fun rangeSkipsUnreadableItems() =
        runTest {
            val notNumber = mapOf("node" to AttributeValue.S(NODE), "ts" to AttributeValue.S("100"))
            val noNode =
                mapOf(
                    "ts" to AttributeValue.N("100"),
                    "temperature_c" to AttributeValue.N("20"),
                )
            givenResponses(
                QueryResponse {
                    items =
                        listOf(
                            item(100, temperature = null),
                            item(101, temperature = "abc"),
                            notNumber,
                            noNode,
                            item(102, humidity = "xyz", battery = "xyz", type = null),
                        )
                },
            )

            val readings = repository.range(NODE, 100, 200)

            assertEquals(listOf(Reading(NODE, 102, null, 21.4, null, null)), readings)
        }

    /** 応答に項目がない (items が null) ときは, 空のリストを返す. */
    @Test
    fun rangeReturnsEmptyWithoutItems() =
        runTest {
            givenResponses(QueryResponse {})

            assertEquals(emptyList(), repository.range(NODE, 100, 200))
        }

    /** 最新の 1 件を, 新しい順に 1 件だけ要求して, 返す. */
    @Test
    fun latestReturnsNewestReading() =
        runTest {
            givenResponses(QueryResponse { items = listOf(item(300)) })

            val reading = repository.latest(NODE)

            assertEquals(Reading(NODE, 300, "switchbot", 21.4, 57.0, 100.0), reading)
            val request = requests.single()
            assertEquals(TABLE, request.tableName)
            assertEquals("#n = :n", request.keyConditionExpression)
            assertEquals(mapOf("#n" to "node"), request.expressionAttributeNames)
            assertEquals(mapOf(":n" to AttributeValue.S(NODE)), request.expressionAttributeValues)
            assertEquals(false, request.scanIndexForward)
            assertEquals(1, request.limit)
        }

    /** 項目がないとき, または, 読めないときは, null を返す. */
    @Test
    fun latestReturnsNullWithoutReadableItem() =
        runTest {
            givenResponses(
                QueryResponse { items = emptyList() },
                QueryResponse { items = listOf(item(300, temperature = null)) },
                QueryResponse {},
            )

            assertNull(repository.latest(NODE))
            assertNull(repository.latest(NODE))
            assertNull(repository.latest(NODE))
        }

    /** [Reading] の各項目を, 読める. */
    @Test
    fun readingExposesItsFields() =
        runTest {
            givenResponses(QueryResponse { items = listOf(item(300)) })

            val reading = repository.latest(NODE)!!

            assertEquals(NODE, reading.node)
            assertEquals(300L, reading.ts)
            assertEquals("switchbot", reading.type)
            assertEquals(21.4, reading.temperatureC)
            assertEquals(57.0, reading.humidity)
            assertEquals(100.0, reading.battery)
        }
}
