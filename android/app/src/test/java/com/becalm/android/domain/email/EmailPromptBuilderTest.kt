package com.becalm.android.domain.email

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [EmailPromptBuilder] (Wave 3 plan §6.5).
 *
 * Uses Robolectric for the `res/raw/email_system_prompt.txt` load — the plain JVM
 * runtime cannot resolve resource IDs so the template must come via
 * [androidx.test.core.app.ApplicationProvider.getApplicationContext].
 *
 * Spec refs: EMAIL-001 (system context shape), EMAIL-005 (quoted_text section),
 * EMAIL-008 (prompt construction).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class EmailPromptBuilderTest {

    private lateinit var builder: EmailPromptBuilder

    @Before
    fun setUp() {
        builder = EmailPromptBuilder(
            context = ApplicationProvider.getApplicationContext(),
        )
    }

    @Test
    fun `buildSystemContext INBOX folder yields default_direction take`() {
        val prompt = builder.buildSystemContext(
            folder = "INBOX",
            phoneE164Self = "+821012345678",
            displayNameOverride = "Alice",
        )

        assertTrue(
            "INBOX system prompt must contain `default_direction: take`, was:\n$prompt",
            prompt.contains("default_direction: take"),
        )
        assertTrue(prompt.contains("folder: INBOX"))
    }

    @Test
    fun `buildSystemContext SENT folder yields default_direction give`() {
        val prompt = builder.buildSystemContext(
            folder = "SENT",
            phoneE164Self = "+821012345678",
            displayNameOverride = "Alice",
        )

        assertTrue(
            "SENT system prompt must contain `default_direction: give`, was:\n$prompt",
            prompt.contains("default_direction: give"),
        )
        assertTrue(prompt.contains("folder: SENT"))
    }

    @Test
    fun `buildSystemContext with null phoneE164Self substitutes not_set placeholder`() {
        val prompt = builder.buildSystemContext(
            folder = "INBOX",
            phoneE164Self = null,
            displayNameOverride = "Alice",
        )

        assertTrue(
            "Null phoneE164Self must render as (not_set), was:\n$prompt",
            prompt.contains("user.phone_e164_self: (not_set)"),
        )
    }

    @Test
    fun `buildSystemContext with null displayNameOverride substitutes not_set placeholder`() {
        val prompt = builder.buildSystemContext(
            folder = "INBOX",
            phoneE164Self = "+821012345678",
            displayNameOverride = null,
        )

        assertTrue(
            "Null displayNameOverride must render as (not_set), was:\n$prompt",
            prompt.contains("user.display_name_override: (not_set)"),
        )
    }

    @Test
    fun `buildSystemContext with both user fields present interpolates verbatim`() {
        val prompt = builder.buildSystemContext(
            folder = "INBOX",
            phoneE164Self = "+821012345678",
            displayNameOverride = "Alice Example",
        )

        assertTrue(prompt.contains("user.phone_e164_self: +821012345678"))
        assertTrue(prompt.contains("user.display_name_override: Alice Example"))
        assertFalse(prompt.contains("{phone_e164_self}"))
        assertFalse(prompt.contains("{display_name_override}"))
    }

    @Test
    fun `buildUserContext with null quotedText renders as none`() {
        val prompt = builder.buildUserContext(
            subject = "Re: hello",
            from = "alice@example.com",
            to = "bob@example.com",
            snippet = "short snippet",
            commitmentText = "let me follow up tomorrow",
            quotedText = null,
        )

        assertTrue(
            "Null quotedText must render as (none), was:\n$prompt",
            prompt.contains("quoted_text:\n(none)"),
        )
    }

    @Test
    fun `buildUserContext with all fields present renders every field and sections`() {
        val prompt = builder.buildUserContext(
            subject = "Re: Quarterly review",
            from = "manager@example.com",
            to = "employee@example.com",
            snippet = "Looking forward to seeing you",
            commitmentText = "I will send the deck by Friday",
            quotedText = "On Mon, Dec 18 John wrote: earlier thread",
        )

        assertTrue(prompt.contains("subject: Re: Quarterly review"))
        assertTrue(prompt.contains("from: manager@example.com"))
        assertTrue(prompt.contains("to: employee@example.com"))
        assertTrue(prompt.contains("snippet: Looking forward to seeing you"))
        assertTrue(prompt.contains("commitment_text:\nI will send the deck by Friday"))
        assertTrue(prompt.contains("quoted_text:\nOn Mon, Dec 18 John wrote: earlier thread"))
    }

    @Test
    fun `buildUserContext with empty commitmentText still emits section separators`() {
        val prompt = builder.buildUserContext(
            subject = "fwd",
            from = "a@b.com",
            to = "c@d.com",
            snippet = null,
            commitmentText = "",
            quotedText = "only quoted content here",
        )

        // commitment_text section still present even when empty — so the prompt's structure
        // is predictable and the LLM does not silently swallow the quoted region.
        assertTrue(prompt.contains("commitment_text:\n"))
        assertTrue(prompt.contains("quoted_text:\nonly quoted content here"))
        assertTrue(prompt.contains("snippet: (not_set)"))
    }
}
