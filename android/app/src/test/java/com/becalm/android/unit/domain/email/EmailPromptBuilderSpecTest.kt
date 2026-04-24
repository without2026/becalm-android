package com.becalm.android.unit.domain.email

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.domain.email.EmailPromptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class EmailPromptBuilderSpecTest {

    private lateinit var builder: EmailPromptBuilder

    @Before
    fun setUp() {
        builder = EmailPromptBuilder(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `system context maps INBOX to take and substitutes user values verbatim`() {
        val prompt = builder.buildSystemContext(
            folder = "INBOX",
            phoneE164Self = "+821012345678",
            displayNameOverride = "Alice Example",
        )

        assertTrue(prompt.contains("folder: INBOX"))
        assertTrue(prompt.contains("default_direction: take"))
        assertTrue(prompt.contains("user.phone_e164_self: +821012345678"))
        assertTrue(prompt.contains("user.display_name_override: Alice Example"))
        assertFalse(prompt.contains("{folder}"))
        assertFalse(prompt.contains("{default_direction}"))
    }

    @Test
    fun `system context maps SENT to give and unknown folders fall back to take`() {
        val sent = builder.buildSystemContext(
            folder = "SENT",
            phoneE164Self = null,
            displayNameOverride = null,
        )
        val unknown = builder.buildSystemContext(
            folder = "ARCHIVE",
            phoneE164Self = null,
            displayNameOverride = null,
        )

        assertTrue(sent.contains("default_direction: give"))
        assertTrue(unknown.contains("folder: ARCHIVE"))
        assertTrue(unknown.contains("default_direction: take"))
    }

    @Test
    fun `system context renders null and blank user fields as not_set`() {
        val prompt = builder.buildSystemContext(
            folder = "INBOX",
            phoneE164Self = " ",
            displayNameOverride = null,
        )

        assertTrue(prompt.contains("user.phone_e164_self: (not_set)"))
        assertTrue(prompt.contains("user.display_name_override: (not_set)"))
    }

    @Test
    fun `user context renders not_set and none placeholders for absent optional fields`() {
        val prompt = builder.buildUserContext(
            subject = null,
            from = "",
            to = null,
            snippet = null,
            commitmentText = "",
            quotedText = "   ",
        )

        assertTrue(prompt.contains("subject: (not_set)"))
        assertTrue(prompt.contains("from: (not_set)"))
        assertTrue(prompt.contains("to: (not_set)"))
        assertTrue(prompt.contains("snippet: (not_set)"))
        assertTrue(prompt.contains("commitment_text:\n\n---"))
        assertTrue(prompt.contains("quoted_text:\n(none)"))
    }

    @Test
    fun `user context preserves primary body and quoted block sections separately`() {
        val prompt = builder.buildUserContext(
            subject = "Re: Quarterly review",
            from = "manager@example.com",
            to = "employee@example.com",
            snippet = "Looking forward to seeing you",
            commitmentText = "I will send the deck by Friday",
            quotedText = "On Mon John wrote: previous thread",
        )

        assertTrue(prompt.contains("subject: Re: Quarterly review"))
        assertTrue(prompt.contains("commitment_text:\nI will send the deck by Friday"))
        assertTrue(prompt.contains("quoted_text:\nOn Mon John wrote: previous thread"))
    }
}
