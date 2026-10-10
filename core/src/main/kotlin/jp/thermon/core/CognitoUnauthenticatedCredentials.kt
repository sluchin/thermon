/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import aws.sdk.kotlin.services.cognitoidentity.CognitoIdentityClient
import aws.sdk.kotlin.services.cognitoidentity.getCredentialsForIdentity
import aws.sdk.kotlin.services.cognitoidentity.getId
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** 期限の何分前から, 認証情報を取り直すか. */
private val REFRESH_BEFORE_EXPIRY: Duration = 5.minutes

/**
 * Cognito の ID プールの未認証の ID から, AWS の一時的な認証情報を受け取る ([CredentialsProvider]).
 *
 * ID (GetId) は, 最初の 1 回だけ取り, 認証情報 (GetCredentialsForIdentity) は, 期限の 5 分前まで,
 * 使い回す. 同時に呼ばれても, 取得は 1 回にする.
 *
 * @property client Cognito Identity のクライアント
 * @property identityPoolId ID プールの ID (例: ap-northeast-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)
 * @property now 今の時刻を返す関数 (テストで, 差し替える)
 */
class CognitoUnauthenticatedCredentials(
    private val client: CognitoIdentityClient,
    private val identityPoolId: String,
    private val now: () -> Instant = { Instant.now() },
) : CredentialsProvider {
    private val mutex = Mutex()
    private var identityId: String? = null
    private var cached: Cached? = null

    /** 受け取った認証情報と, その期限. */
    private class Cached(
        val credentials: Credentials,
        val expiration: Instant,
    )

    /**
     * 認証情報を返す (期限の 5 分前までは, 受け取った認証情報を使い回す).
     *
     * @param attributes SDK が渡す属性 (使わない)
     * @return 認証情報
     * @throws IllegalStateException Cognito の応答に, 必要な項目がないとき
     */
    override suspend fun resolve(attributes: Attributes): Credentials =
        mutex.withLock {
            // 期限の 5 分前までは, 使い回す
            cached?.takeIf { now() + REFRESH_BEFORE_EXPIRY < it.expiration }?.credentials
                ?: fetch().also { cached = it }.credentials
        }

    /**
     * Cognito から, 認証情報を受け取る (ID は, 取っていなければ, 先に取る).
     *
     * @return 認証情報と, その期限
     * @throws IllegalStateException Cognito の応答に, 必要な項目がないとき
     */
    private suspend fun fetch(): Cached {
        val id = identityId ?: fetchIdentityId().also { identityId = it }
        val response = client.getCredentialsForIdentity { identityId = id }
        val c = required(response.credentials, "credentials")
        val expiration = required(c.expiration, "expiration")
        val credentials =
            Credentials(
                accessKeyId = required(c.accessKeyId, "access key ID"),
                secretAccessKey = required(c.secretKey, "secret key"),
                sessionToken = c.sessionToken,
                expiration = expiration,
            )
        return Cached(credentials, expiration)
    }

    /**
     * 未認証の ID を取る.
     *
     * @return ID
     * @throws IllegalStateException Cognito の応答に, ID がないとき
     */
    private suspend fun fetchIdentityId(): String {
        val response =
            client.getId {
                identityPoolId =
                    this@CognitoUnauthenticatedCredentials.identityPoolId
            }
        return required(response.identityId, "identity ID")
    }
}

/**
 * Cognito の応答の項目が, あることを確かめる.
 *
 * @param value 応答の項目
 * @param name 項目の名前 (例外のメッセージに使う)
 * @return 項目の値
 * @throws IllegalStateException 項目がないとき
 */
private fun <T : Any> required(
    value: T?,
    name: String,
): T = checkNotNull(value) { "Cognito did not return the $name" }
