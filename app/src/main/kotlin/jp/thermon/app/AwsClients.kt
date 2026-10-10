/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.app

import aws.sdk.kotlin.services.cognitoidentity.CognitoIdentityClient
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import jp.thermon.core.CognitoUnauthenticatedCredentials
import jp.thermon.core.DynamoDbReadings
import jp.thermon.core.ReadingsRepository

/** AWS のクライアントを作る (設定は, BuildConfig. local.properties から). */
object AwsClients {
    /**
     * Cognito の未認証の ID の認証情報で, DynamoDB から測定値を取り出す入り口を作る.
     *
     * @return 測定値を取り出す入り口
     */
    fun readings(): ReadingsRepository {
        val cognito = CognitoIdentityClient { region = BuildConfig.AWS_REGION }
        val dynamoDb =
            DynamoDbClient {
                region = BuildConfig.AWS_REGION
                credentialsProvider =
                    CognitoUnauthenticatedCredentials(cognito, BuildConfig.IDENTITY_POOL_ID)
            }
        return DynamoDbReadings(dynamoDb, BuildConfig.TABLE_NAME)
    }
}
