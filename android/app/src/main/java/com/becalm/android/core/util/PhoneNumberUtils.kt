package com.becalm.android.core.util

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * E.164 normalization helpers for counterparty phone numbers extracted from Samsung
 * One UI 6.x call-recording filenames / MediaStore metadata.
 *
 * Spec ref: `.spec/data-ingestion.spec.yml` ING-001 — for `source_type='call_recording'`
 * the `person_ref` column stores the counterparty number "normalized to E.164 (null if
 * none)". Callers MUST use [PhoneNumberUtils.toE164OrNull] /
 * [PhoneNumberUtils.extractCounterpartyNumberFromDisplayName]; do not hand-roll parsing
 * logic.
 *
 * ## Contract
 * - Unparseable / invalid inputs **never throw** — they return null. ING-001: "없으면 null".
 * - `defaultRegion` defaults to "KR" because BeCalm v2 is a B2B 국내 타겟. International
 *   numbers written in full E.164 form (`+1…`) are still recognized correctly because
 *   libphonenumber honours the leading `+`.
 * - [PhoneNumberUtil.getInstance] is singleton-safe and lazy on first call; we do not
 *   cache the reference in a property because the library already memoises it.
 */
internal object PhoneNumberUtils {

    /**
     * Normalizes [raw] to its E.164 representation.
     *
     * Returns null when:
     * - [raw] is null or blank (after trim).
     * - libphonenumber's parser rejects the input ([NumberParseException]).
     * - The parsed number fails [PhoneNumberUtil.isValidNumber] (e.g. wrong length,
     *   impossible country code).
     *
     * @param raw Raw input string such as `"010-1234-5678"`, `"+82 10 1234 5678"`, or
     *   `"01012345678"`.
     * @param defaultRegion ISO 3166-1 alpha-2 region code used when [raw] has no
     *   explicit country code. Defaults to `"KR"`.
     * @return E.164 string like `"+821012345678"`, or null if parsing / validation fails.
     */
    fun toE164OrNull(raw: String?, defaultRegion: String = "KR"): String? {
        if (raw.isNullOrBlank()) return null
        val util = PhoneNumberUtil.getInstance()
        val parsed = try {
            util.parse(raw, defaultRegion)
        } catch (_: NumberParseException) {
            return null
        }
        if (!util.isValidNumber(parsed)) return null
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /**
     * Scans [displayName] for the first valid phone number and returns its E.164 form.
     *
     * Samsung One UI 6.x stores call recordings with filenames like:
     * - `Call_010-1234-5678_20250415_0830.m4a`
     * - `Call recording John Doe 0104567890_250415.m4a`
     * - `통화녹음 홍길동_010-1234-5678_….m4a`
     *
     * A hand-rolled regex over these variants is fragile (spaces, Hangul names, legacy
     * layouts). Instead we delegate to [PhoneNumberUtil.findNumbers] with the default
     * region `"KR"`, iterate matches, and return the first one whose `validationResult`
     * is [PhoneNumberUtil.ValidationResult.IS_POSSIBLE] *and* whose parsed number passes
     * [PhoneNumberUtil.isValidNumber]. This tolerates separator variety and arbitrary
     * surrounding text without additional code.
     *
     * @return E.164 string of the first valid number found, or null if none.
     */
    fun extractCounterpartyNumberFromDisplayName(displayName: String): String? {
        if (displayName.isBlank()) return null
        val util = PhoneNumberUtil.getInstance()
        val iterator = util.findNumbers(displayName, "KR").iterator()
        while (iterator.hasNext()) {
            val match = iterator.next()
            val number = match.number()
            if (util.isValidNumber(number)) {
                return util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        }
        return null
    }
}
