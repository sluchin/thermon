/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest

/** 属性名: 機器の識別子 (パーティションキー). */
private const val ATTR_NODE = "node"

/** 属性名: 受信した時刻 (ソートキー). */
private const val ATTR_TS = "ts"

/** 属性名: 機器の種類. */
private const val ATTR_TYPE = "type"

/** 属性名: 温度. */
private const val ATTR_TEMPERATURE = "temperature_c"

/** 属性名: 湿度. */
private const val ATTR_HUMIDITY = "humidity"

/** 属性名: 電池残量. */
private const val ATTR_BATTERY = "battery"

/** 属性名: 気圧. */
private const val ATTR_PRESSURE = "pressure_hpa"

/**
 * DynamoDB のテーブル thermo-readings から, 測定値を取り出す ([ReadingsRepository]).
 *
 * Query だけを使う (未認証ロールの権限は, dynamodb:Query だけ). 結果が 1 回の上限 (1 MB) を超えるときは,
 * lastEvaluatedKey で続きを取り出す.
 *
 * @property client DynamoDB のクライアント
 * @property tableName テーブル名
 */
class DynamoDbReadings(
    private val client: DynamoDbClient,
    private val tableName: String,
) : ReadingsRepository {
    override suspend fun range(
        node: String,
        fromTs: Long,
        toTs: Long,
    ): List<Reading> {
        val readings = mutableListOf<Reading>()
        var startKey: Map<String, AttributeValue>? = null
        do {
            val response =
                client.query(
                    QueryRequest {
                        tableName = this@DynamoDbReadings.tableName
                        keyConditionExpression = "#n = :n AND #t BETWEEN :a AND :b"
                        expressionAttributeNames = mapOf("#n" to ATTR_NODE, "#t" to ATTR_TS)
                        expressionAttributeValues =
                            mapOf(
                                ":n" to AttributeValue.S(node),
                                ":a" to AttributeValue.N(fromTs.toString()),
                                ":b" to AttributeValue.N(toTs.toString()),
                            )
                        exclusiveStartKey = startKey
                    },
                )
            response.items.orEmpty().mapNotNullTo(readings) { toReading(it) }
            startKey = response.lastEvaluatedKey
        } while (startKey != null)
        return readings
    }

    override suspend fun latest(node: String): Reading? {
        val response =
            client.query(
                QueryRequest {
                    tableName = this@DynamoDbReadings.tableName
                    keyConditionExpression = "#n = :n"
                    expressionAttributeNames = mapOf("#n" to ATTR_NODE)
                    expressionAttributeValues = mapOf(":n" to AttributeValue.S(node))
                    scanIndexForward = false
                    limit = 1
                },
            )
        return response.items.orEmpty().firstNotNullOfOrNull { toReading(it) }
    }
}

/**
 * DynamoDB の 1 件を, [Reading] にする.
 *
 * node, ts, temperature_c のどれかがない, または, 数値として読めないときは, null (その項目は, 除く).
 *
 * @param item DynamoDB の 1 件
 * @return [Reading]. 読めないときは, null
 */
internal fun toReading(item: Map<String, AttributeValue>): Reading? {
    val node = (item[ATTR_NODE] as? AttributeValue.S)?.value
    val ts = number(item[ATTR_TS])?.toLong()
    val temperature = number(item[ATTR_TEMPERATURE])
    if (node == null || ts == null || temperature == null) {
        return null
    }
    return Reading(
        node = node,
        ts = ts,
        type = (item[ATTR_TYPE] as? AttributeValue.S)?.value,
        temperatureC = temperature,
        humidity = number(item[ATTR_HUMIDITY]),
        battery = number(item[ATTR_BATTERY]),
        pressureHpa = number(item[ATTR_PRESSURE]),
    )
}

/**
 * DynamoDB の数値の属性を, Double にする.
 *
 * @param value 属性の値 (数値 (N) でなければ, null)
 * @return 数値. 数値の属性でない, または, 読めないときは, null
 */
private fun number(value: AttributeValue?): Double? =
    (value as? AttributeValue.N)?.value?.toDoubleOrNull()
