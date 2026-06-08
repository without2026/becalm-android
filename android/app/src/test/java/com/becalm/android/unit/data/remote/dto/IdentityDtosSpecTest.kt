package com.becalm.android.unit.data.remote.dto

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.remote.dto.OnboardingSelfIdentityCommitResponseDto
import com.becalm.android.data.remote.dto.SelfIdentityAnchorsResponseDto
import com.becalm.android.data.remote.dto.UserProfileResponseDto
import com.squareup.moshi.Moshi
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityDtosSpecTest {

    private val moshi = Moshi.Builder()
        .addBecalmAdapters()
        .build()

    @Test
    fun `user profile response parses profile timestamps and defaults`() {
        val json = """
            {
              "data": {
                "user_id": "user-1",
                "display_name": "Jake",
                "display_name_override": "Jake",
                "display_name_source": "manual",
                "phone_e164_self": "+821012341234",
                "timezone": "Asia/Seoul",
                "preferred_locale": "ko",
                "onboarding_completed_at": "2026-06-03T01:00:00Z",
                "created_at": "2026-06-03T00:00:00Z",
                "updated_at": "2026-06-03T01:00:00Z"
              }
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(UserProfileResponseDto::class.java).fromJson(json))

        assertEquals("user-1", response.data.userId)
        assertEquals("Jake", response.data.displayNameOverride)
        assertEquals("+821012341234", response.data.phoneE164Self)
        assertEquals(Instant.parse("2026-06-03T01:00:00Z"), response.data.onboardingCompletedAt)
    }

    @Test
    fun `onboarding self identity response parses profile anchors and recommendation`() {
        val json = """
            {
              "data": {
                "profile": {
                  "user_id": "user-1",
                  "display_name": "Jake",
                  "display_name_override": "Jake",
                  "display_name_source": "google_auth",
                  "phone_e164_self": null,
                  "timezone": "Asia/Seoul",
                  "preferred_locale": "ko",
                  "onboarding_completed_at": null,
                  "created_at": "2026-06-03T00:00:00Z",
                  "updated_at": "2026-06-03T00:00:00Z"
                },
                "anchors": [
                  {
                    "id": "anchor-email",
                    "user_id": "user-1",
                    "anchor_type": "email",
                    "normalized_value": "jake@example.com",
                    "display_value": "jake@example.com",
                    "source": "google_auth",
                    "scope": "global",
                    "source_connection_id": null,
                    "source_event_id": null,
                    "trust": "verified",
                    "status": "active",
                    "created_at": "2026-06-03T00:00:00Z",
                    "updated_at": "2026-06-03T00:00:00Z"
                  }
                ],
                "email_connection_recommendation": {
                  "provider": "naver_imap",
                  "reason": "korean_email_domain"
                }
              }
            }
        """.trimIndent()

        val response = requireNotNull(
            moshi.adapter(OnboardingSelfIdentityCommitResponseDto::class.java).fromJson(json),
        )

        assertEquals("Jake", response.data.profile.displayName)
        assertEquals("anchor-email", response.data.anchors.single().id)
        assertEquals("naver_imap", response.data.emailConnectionRecommendation?.provider)
        assertNull(response.data.profile.phoneE164Self)
    }

    @Test
    fun `self identity anchors response parses one ten hundred rows`() {
        listOf(1, 10, 100).forEach { scale ->
            val rows = (0 until scale).joinToString(",") { index ->
                """
                    {
                      "id": "anchor-$index",
                      "user_id": "user-1",
                      "anchor_type": "email",
                      "normalized_value": "person$index@example.com",
                      "display_value": "Person $index",
                      "source": "user_profile",
                      "scope": "global",
                      "source_connection_id": null,
                      "source_event_id": null,
                      "trust": "user_confirmed",
                      "status": "active",
                      "created_at": "2026-06-03T00:00:00Z",
                      "updated_at": "2026-06-03T00:00:00Z"
                    }
                """.trimIndent()
            }
            val json = """{"data": [$rows]}"""

            val response = requireNotNull(moshi.adapter(SelfIdentityAnchorsResponseDto::class.java).fromJson(json))

            assertEquals(scale, response.data.size)
            assertEquals("anchor-0", response.data.first().id)
            assertEquals("person0@example.com", response.data.first().normalizedValue)
        }
    }
}
