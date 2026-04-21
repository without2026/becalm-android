package com.becalm.android.domain.email

import android.content.Context
import com.becalm.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the two prompt strings
 * ([com.becalm.android.domain.extractor.GeminiNanoExtractor.extract] requires) for an email
 * commitment-extraction call:
 *
 * - `systemContext` — loaded from `res/raw/email_system_prompt.txt` and rendered with
 *   per-user placeholders (`{folder}`, `{default_direction}`, `{phone_e164_self}`,
 *   `{display_name_override}`).
 * - `userContext` — per-event payload assembled inline in this class: subject / from / to /
 *   snippet / primary message body / quoted reply block.
 *
 * The system-prompt template lives in `res/raw/` (not an inline Kotlin constant) so that prompt
 * edits surface as isolated diffs in PR review and can be audited via `git log -p
 * res/raw/email_system_prompt.txt`. Android's resource compiler bakes the file into the APK
 * and exposes it through [android.content.res.Resources.openRawResource] — there is no runtime
 * file-IO against the user's storage.
 *
 * The template is read once per process and memoised: subsequent [buildSystemContext] calls
 * operate on the cached string, avoiding repeated I/O on the hot path (one worker enqueue per
 * email).
 *
 * ## Contract: placeholder substitution
 * Null or blank values are replaced with the literal token `(not_set)` so the LLM can tell
 * "the user did not provide a phone number" apart from "the substitution broke and there is
 * an empty slot". Same convention for the `{quoted_text}` slot in [buildUserContext], which
 * uses `(none)` to signal "no quoted reply block was detected".
 *
 * Spec refs: EMAIL-001 (system context shape), EMAIL-005 (quoted_text section), EMAIL-008
 * (prompt construction).
 */
@Singleton
public class EmailPromptBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cachedTemplate: String? = null

    /**
     * Returns the rendered system prompt for an email extraction call.
     *
     * @param folder Either `"INBOX"` or `"SENT"` — the only two folder labels the ingestion
     *   pipeline produces. Unknown values are passed through untouched so the LLM can still
     *   reason about them, but `default_direction` falls back to `take` because INBOX is the
     *   common case. See [resolveDefaultDirection].
     * @param phoneE164Self The user's own phone number in E.164 format, or null when the
     *   user has not supplied one in onboarding.
     * @param displayNameOverride The user's preferred display name, or null when the user
     *   has not overridden the default profile name.
     * @return A multi-line prompt string with all placeholders substituted. Never null.
     */
    public fun buildSystemContext(
        folder: String,
        phoneE164Self: String?,
        displayNameOverride: String?,
    ): String {
        val template = loadTemplate()
        return template
            .replace(PLACEHOLDER_FOLDER, folder)
            .replace(PLACEHOLDER_DEFAULT_DIRECTION, resolveDefaultDirection(folder))
            .replace(PLACEHOLDER_PHONE_E164_SELF, phoneE164Self.orNotSet())
            .replace(PLACEHOLDER_DISPLAY_NAME_OVERRIDE, displayNameOverride.orNotSet())
    }

    /**
     * Assembles the per-event user prompt section.
     *
     * @param subject Email subject, or null when absent.
     * @param from Sender email address, or null.
     * @param to First/primary recipient email address, or null.
     * @param snippet 200-character snippet already stored on the raw event, or null.
     * @param commitmentText Primary message body — the region the LLM is allowed to extract
     *   commitments from. May be empty (e.g. full-quote reply) but never null; callers pass
     *   an empty string rather than omitting the field.
     * @param quotedText Quoted reply block from [QuotedBlockSplitter], or null when no
     *   quoted region was detected. Renders as the literal `(none)` in the prompt.
     * @return A multi-line prompt string ready to be passed to
     *   [com.becalm.android.domain.extractor.GeminiNanoExtractor.extract] as the
     *   `userContext` argument.
     */
    public fun buildUserContext(
        subject: String?,
        from: String?,
        to: String?,
        snippet: String?,
        commitmentText: String,
        quotedText: String?,
    ): String = buildString {
        append("subject: ").append(subject.orNotSet()).append('\n')
        append("from: ").append(from.orNotSet()).append('\n')
        append("to: ").append(to.orNotSet()).append('\n')
        append("snippet: ").append(snippet.orNotSet()).append('\n')
        append("---\n")
        append("commitment_text:\n")
        append(commitmentText).append('\n')
        append("---\n")
        append("quoted_text:\n")
        append(quotedText?.takeIf { it.isNotBlank() } ?: "(none)")
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads [R.raw.email_system_prompt] once and caches the string for the lifetime of this
     * singleton. The double-checked read/write uses [Volatile] + local assignment to avoid
     * tearing on concurrent first-call scenarios.
     */
    private fun loadTemplate(): String {
        cachedTemplate?.let { return it }
        val loaded = context.resources.openRawResource(R.raw.email_system_prompt).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
        cachedTemplate = loaded
        return loaded
    }

    private fun resolveDefaultDirection(folder: String): String = when (folder) {
        FOLDER_SENT -> DEFAULT_DIRECTION_GIVE
        else -> DEFAULT_DIRECTION_TAKE
    }

    private fun String?.orNotSet(): String =
        if (this.isNullOrBlank()) NOT_SET else this

    private companion object {
        private const val FOLDER_SENT: String = "SENT"

        private const val DEFAULT_DIRECTION_TAKE: String = "take"
        private const val DEFAULT_DIRECTION_GIVE: String = "give"

        private const val NOT_SET: String = "(not_set)"

        private const val PLACEHOLDER_FOLDER: String = "{folder}"
        private const val PLACEHOLDER_DEFAULT_DIRECTION: String = "{default_direction}"
        private const val PLACEHOLDER_PHONE_E164_SELF: String = "{phone_e164_self}"
        private const val PLACEHOLDER_DISPLAY_NAME_OVERRIDE: String = "{display_name_override}"
    }
}
