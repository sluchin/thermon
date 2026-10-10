/*
 * Copyright (c) 2026 Tetsuya Higashi
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package jp.thermon.core

import aws.sdk.kotlin.services.cognitoidentity.CognitoIdentityClient
import aws.sdk.kotlin.services.cognitoidentity.model.Credentials
import aws.sdk.kotlin.services.cognitoidentity.model.GetCredentialsForIdentityRequest
import aws.sdk.kotlin.services.cognitoidentity.model.GetCredentialsForIdentityResponse
import aws.sdk.kotlin.services.cognitoidentity.model.GetIdRequest
import aws.sdk.kotlin.services.cognitoidentity.model.GetIdResponse
import aws.smithy.kotlin.runtime.collections.emptyAttributes
import aws.smithy.kotlin.runtime.time.Instant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.hours

/** 試験の ID プールの ID. */
private const val POOL_ID = "ap-northeast-1:00000000-0000-0000-0000-000000000000"

/** 試験の, 最初の時刻 (UNIX 時刻 [s]). */
private const val START_SEC = 1_000_000L

/** 試験で, 認証情報の期限までの長さ (1 時間) [s]. */
private const val VALID_SEC = 3600L

/** 期限の何秒前から, 取り直すか (5 分). */
private const val REFRESH_SEC = 300L

/** [CognitoUnauthenticatedCredentials] の単体テスト (Cognito のクライアントは, 偽物). */
class CognitoUnauthenticatedCredentialsTest {
    private val client = mockk<CognitoIdentityClient>()
    private var nowSec = START_SEC
    private var counter = 0
    private val provider =
        CognitoUnauthenticatedCredentials(client, POOL_ID) {
            Instant.fromEpochSeconds(nowSec)
        }

    /** 認証情報の応答を作る (期限は, 今から [VALID_SEC] 秒後. [counter] で, 毎回, 値を変える). */
    private fun response(
        accessKeyId: String? = "AK$counter",
        secretKey: String? = "SK$counter",
        withExpiration: Boolean = true,
    ) = GetCredentialsForIdentityResponse {
        credentials =
            Credentials {
                this.accessKeyId = accessKeyId
                this.secretKey = secretKey
                sessionToken = "TOKEN$counter"
                if (withExpiration) {
                    expiration = Instant.fromEpochSeconds(nowSec + VALID_SEC)
                }
            }
    }

    /** 認証情報を取って, 例外になることを確かめて, その例外を返す. */
    private suspend fun resolveFailure(): IllegalStateException =
        assertFailsWith<IllegalStateException> { provider.resolve(emptyAttributes()) }

    /** ID の取得の応答を設定する. */
    private fun givenIdentity(identityId: String? = "id-1") {
        coEvery { client.getId(any<GetIdRequest>()) } returns
            GetIdResponse { this.identityId = identityId }
    }

    /** 認証情報の取得の応答を, 呼ぶたびに [counter] を増やして返すようにする. */
    private fun givenCredentials() {
        coEvery {
            client.getCredentialsForIdentity(
                any<GetCredentialsForIdentityRequest>(),
            )
        } answers
            {
                counter++
                response()
            }
    }

    /** ID と認証情報を, ID プールから取って, 認証情報を返す. */
    @Test
    fun resolveFetchesIdentityAndCredentials() =
        runTest {
            val idRequest = slot<GetIdRequest>()
            val credentialsRequest = slot<GetCredentialsForIdentityRequest>()
            coEvery { client.getId(capture(idRequest)) } returns
                GetIdResponse { identityId = "id-1" }
            coEvery { client.getCredentialsForIdentity(capture(credentialsRequest)) } returns
                response()

            val credentials = provider.resolve(emptyAttributes())

            assertEquals("AK0", credentials.accessKeyId)
            assertEquals("SK0", credentials.secretAccessKey)
            assertEquals("TOKEN0", credentials.sessionToken)
            assertEquals(Instant.fromEpochSeconds(START_SEC + VALID_SEC), credentials.expiration)
            assertEquals(POOL_ID, idRequest.captured.identityPoolId)
            assertEquals("id-1", credentialsRequest.captured.identityId)
        }

    /** 期限の 5 分前までは, 取り直さずに, 同じ認証情報を返す. */
    @Test
    fun resolveReusesCredentialsBeforeRefreshTime() =
        runTest {
            givenIdentity()
            givenCredentials()

            val first = provider.resolve(emptyAttributes())
            nowSec = START_SEC + VALID_SEC - REFRESH_SEC - 1
            val second = provider.resolve(emptyAttributes())

            assertEquals(first, second)
            coVerify(exactly = 1) { client.getId(any<GetIdRequest>()) }
            coVerify(
                exactly = 1,
            ) { client.getCredentialsForIdentity(any<GetCredentialsForIdentityRequest>()) }
        }

    /** 期限の 5 分前になったら, 認証情報だけを取り直す (ID は, 取り直さない). */
    @Test
    fun resolveRefreshesCredentialsNearExpiry() =
        runTest {
            givenIdentity()
            givenCredentials()

            val first = provider.resolve(emptyAttributes())
            nowSec = START_SEC + VALID_SEC - REFRESH_SEC
            val second = provider.resolve(emptyAttributes())

            assertNotEquals(first.accessKeyId, second.accessKeyId)
            assertEquals("AK2", second.accessKeyId)
            coVerify(exactly = 1) { client.getId(any<GetIdRequest>()) }
            coVerify(
                exactly = 2,
            ) { client.getCredentialsForIdentity(any<GetCredentialsForIdentityRequest>()) }
        }

    /** 同時に呼ばれても, 取得は 1 回だけで, 同じ認証情報を返す. */
    @Test
    fun resolveFetchesOnceForConcurrentCalls() =
        runTest {
            givenIdentity()
            givenCredentials()

            val results =
                awaitAll(
                    async { provider.resolve(emptyAttributes()) },
                    async { provider.resolve(emptyAttributes()) },
                )

            assertEquals(results[0], results[1])
            coVerify(
                exactly = 1,
            ) { client.getCredentialsForIdentity(any<GetCredentialsForIdentityRequest>()) }
        }

    /** ID が, 応答にないときは, 例外にする. */
    @Test
    fun resolveFailsWithoutIdentityId() =
        runTest {
            givenIdentity(identityId = null)

            val error = resolveFailure()

            assertEquals("Cognito did not return the identity ID", error.message)
        }

    /** 認証情報が, 応答にないときは, 例外にする. */
    @Test
    fun resolveFailsWithoutCredentials() =
        runTest {
            givenIdentity()
            coEvery {
                client.getCredentialsForIdentity(any<GetCredentialsForIdentityRequest>())
            } returns GetCredentialsForIdentityResponse {}

            val error = resolveFailure()

            assertEquals("Cognito did not return the credentials", error.message)
        }

    /** 期限, アクセスキー, シークレットキーのどれかがないときは, 例外にする. */
    @Test
    fun resolveFailsWithoutRequiredFields() =
        runTest {
            givenIdentity()
            val cases =
                listOf(
                    response(withExpiration = false) to "Cognito did not return the expiration",
                    response(accessKeyId = null) to "Cognito did not return the access key ID",
                    response(secretKey = null) to "Cognito did not return the secret key",
                )

            for ((invalid, message) in cases) {
                coEvery {
                    client.getCredentialsForIdentity(any<GetCredentialsForIdentityRequest>())
                } returns invalid

                val error =
                    assertFailsWith<IllegalStateException> { provider.resolve(emptyAttributes()) }

                assertEquals(message, error.message)
            }
        }

    /** 取得に失敗したあとに呼ぶと, 取り直す (失敗を, 覚えない). */
    @Test
    fun resolveRetriesAfterFailure() =
        runTest {
            givenIdentity()
            coEvery {
                client.getCredentialsForIdentity(any<GetCredentialsForIdentityRequest>())
            } returns GetCredentialsForIdentityResponse {} andThen response()

            assertFailsWith<IllegalStateException> { provider.resolve(emptyAttributes()) }
            val credentials = provider.resolve(emptyAttributes())

            assertEquals("AK0", credentials.accessKeyId)
        }

    /** 時計を渡さないときは, 実際の時刻 (Instant.now()) で, 期限を判断する. */
    @Test
    fun resolveUsesTheSystemClockByDefault() =
        runTest {
            val defaultProvider = CognitoUnauthenticatedCredentials(client, POOL_ID)
            givenIdentity()
            coEvery {
                client.getCredentialsForIdentity(
                    any<GetCredentialsForIdentityRequest>(),
                )
            } returns
                GetCredentialsForIdentityResponse {
                    credentials =
                        Credentials {
                            accessKeyId = "AK"
                            secretKey = "SK"
                            expiration = Instant.now() + 1.hours
                        }
                }

            val first = defaultProvider.resolve(emptyAttributes())
            val second = defaultProvider.resolve(emptyAttributes())

            assertEquals("AK", first.accessKeyId)
            assertEquals(first, second)
            coVerify(
                exactly = 1,
            ) { client.getCredentialsForIdentity(any<GetCredentialsForIdentityRequest>()) }
        }
}
