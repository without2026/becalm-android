/**
 * SP-48: Glass card rendering a single commitment with direction cast, D-N badge,
 * and status chip.
 *
 * The card reads from design token spec §4 (direction) and §5 (D-N badges, state
 * colors). All user-visible strings come in as parameters — no hardcoded Korean or
 * English copy inside the composable.
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.core.util.KST
import com.becalm.android.ui.theme.BecalmStateColors
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors
import com.becalm.android.ui.theme.glassPanel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

// ─── CommitmentCard ───────────────────────────────────────────────────────────

/**
 * Renders a single commitment as a glass card with a direction-cast left stripe,
 * a compact D-N urgency badge, an action status chip, and an optional mark-done
 * icon button.
 *
 * Accessibility: the card merges descendants into one semantic node. When [onClick]
 * is provided the node carries [Role.Button] and a [contentDescription] composed
 * from direction and title.
 *
 * @param title         Commitment title text (localized by caller). Must be a
 *                      resolved display label. Never pass a raw email address, phone
 *                      number, or internal identifier — the TalkBack accessibility
 *                      description includes this value verbatim.
 * @param direction     Direction string — `"give"` renders warm amber cast,
 *                      `"take"` renders cool slate cast; any other value falls back
 *                      to `colorScheme.outline`.
 * @param derivedStatus Status string driving the chip and card alpha. Accepted values:
 *                      `"PENDING"`, `"REMINDED"`, `"FOLLOWED_UP"`, `"COMPLETED"`,
 *                      `"OVERDUE"`, `"CANCELLED"` (spec-aligned action_state keys,
 *                      uppercased). Unknown values map to pending styling.
 * @param dueAt         Optional due instant (UTC epoch via kotlinx.datetime.Instant) for
 *                      the D-N badge. `null` = no badge shown. Converted to an
 *                      Asia/Seoul calendar date before diffing against today-in-KST
 *                      (data-model.yml:132-144, VOI-003 KST rendering rule).
 * @param dueIsApproximate When true the D-N badge switches to the `D~N` form (tilde
 *                      between the `D` and the count) to signal that the due date was
 *                      inferred from a fuzzy hint — commitment-management.spec.yml:39-43.
 *                      Default false.
 * @param dueHint       Optional verbatim due-date expression (e.g. "월말", "다음주")
 *                      captured from the source event. Rendered beneath the title
 *                      ONLY when [dueIsApproximate] is `true` AND the hint is a
 *                      non-blank string — this auxiliary line exists to help users
 *                      interpret inferred deadlines. Exact deadlines already carry
 *                      full information via the D-N badge, so rendering the raw
 *                      hint text there would be noise. Contract pinned by
 *                      commitment-management.spec.yml:9,13 ("approximate-only").
 * @param counterpartyDisplayName
 *                      Resolved, human-readable display name of the counterparty shown
 *                      below the title, or `null` to omit the line. Must already be
 *                      enriched by the caller (ViewModel) — this composable is a pure UI
 *                      leaf and performs NO personRef → display name resolution. The
 *                      ViewModel is responsible for joining the raw `person_ref` against
 *                      `PersonEnrichmentDao` (or equivalent) and passing the result here.
 *                      Never pass a raw email address, phone number, or internal
 *                      identifier — the TalkBack accessibility description and the
 *                      on-screen text render this value verbatim.
 * @param modifier      Optional [Modifier] applied to the card root.
 * @param onClick       Optional click handler; when non-null the card is tappable.
 * @param onMarkDone    Optional callback for the inline check icon button. The button
 *                      is only rendered when non-null AND [derivedStatus] is not
 *                      `"COMPLETED"` or `"CANCELLED"` (terminal states).
 * @param isManual      When true, the card renders a `📝 수동 추가` chip adjacent to
 *                      the title (spec MAN-004). The chip helps users distinguish
 *                      user-created rows from LLM-extracted rows while keeping the
 *                      overall lifecycle rendering identical. Default false.
 */
@Composable
public fun CommitmentCard(
    title: String,
    direction: String,
    derivedStatus: String,
    dueAt: Instant?,
    counterpartyDisplayName: String?,
    modifier: Modifier = Modifier,
    dueIsApproximate: Boolean = false,
    dueHint: String? = null,
    isManual: Boolean = false,
    onClick: (() -> Unit)? = null,
    onMarkDone: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme

    // Direction stripe color
    val stripeColor = when (direction.lowercase()) {
        "give" -> colors.directionGive.border
        "take" -> colors.directionTake.border
        else -> colorScheme.outline
    }

    // Status → alpha; chip visibility. COMPLETED and CANCELLED both dim per spec
    // CMT-009 — the user has closed the row one way or the other. OVERDUE stays at
    // full alpha so it keeps pulling the user's attention (they still need to act).
    val normalized = derivedStatus.uppercase()
    val isCompleted = normalized == "COMPLETED"
    val isCancelled = normalized == "CANCELLED"
    val isTerminal = isCompleted || isCancelled
    val cardAlpha = if (isTerminal) 0.6f else 1.0f
    // Chip is always shown so the terminal-state reason is visible even when dimmed.
    val showChip = true

    // D-N badge — single computation memoised on dueAt. Convert dueAt to an Asia/Seoul
    // calendar date, diff against today-in-KST, and render per
    // commitment-management.spec.yml:39-43:
    //   D-0  same-day exact
    //   D-N  future N days exact
    //   D+N  overdue by N days
    //   D~N  approximate (tilde sits BETWEEN the `D` and the count, not as a leading
    //        prefix on the whole label — distinguishes spec-correct `D~3` from the
    //        prior `~D-3` drift)
    //
    // [KST] is the canonical business-calendar zone shared with
    // TodayViewModel.endOfTodayEpochMs — do not substitute TimeZone.currentSystemDefault
    // here.
    val daysUntil: Int? = remember(dueAt) {
        daysUntilInKst(dueAt = dueAt, now = Clock.System.now(), zone = KST)
    }
    val badge: Pair<String, BecalmStateColors>? = daysUntil?.let { days ->
        val stateColors = when {
            days == 0 -> colors.dayBadgeToday
            days in 1..3 -> colors.dayBadgeSoon
            days >= 4 -> colors.dayBadgeUpcoming
            else -> colors.dayBadgeOverdue // negative = past due
        }
        val label = formatDayBadgeLabel(days = days, approximate = dueIsApproximate)
        label to stateColors
    }

    val semanticsDesc = "$direction $title"
    val showMarkDone = onMarkDone != null && !isTerminal

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .alpha(cardAlpha)
            .glassPanel()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = onClick)
                } else Modifier
            )
            .semantics(mergeDescendants = true) {
                contentDescription = semanticsDesc
                if (onClick != null) role = Role.Button
            },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left direction stripe
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(stripeColor),
            )

            // Card body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // Top row: title + badge/mark-done
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    // Manual-add chip (MAN-004). Rendered before the D-N badge so the
                    // `📝 수동 추가` label sits nearest the title — it identifies the
                    // origin of the row, whereas the D-N badge is a deadline indicator.
                    // Re-uses the pending-action token for a neutral tone; the chip is
                    // intentionally not colour-coded by lifecycle state (source_type is
                    // orthogonal to action_state per MAN-006 invariant).
                    if (isManual) {
                        Spacer(modifier = Modifier.width(8.dp))
                        PillBadge(
                            label = stringResource(R.string.commitment_manual_badge),
                            stateColors = colors.actionStatePending,
                            horizontalPadding = 6.dp,
                            verticalPadding = 2.dp,
                        )
                    }
                    // D-N badge
                    if (badge != null) {
                        val (badgeLabel, badgeColors) = badge
                        Spacer(modifier = Modifier.width(8.dp))
                        PillBadge(
                            label = badgeLabel,
                            stateColors = badgeColors,
                            horizontalPadding = 6.dp,
                            verticalPadding = 2.dp,
                        )
                    }
                    // Mark-done button — showMarkDone guarantees onMarkDone != null
                    if (showMarkDone && onMarkDone != null) {
                        IconButton(
                            onClick = onMarkDone,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Mark done",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // Due-hint line — original verbatim expression from the source event
                // (e.g. "월말"). Gated to approximate deadlines per
                // commitment-management.spec.yml:9,13: on exact deadlines the D-N
                // badge already communicates the date unambiguously, so rendering
                // the raw hint would be duplicative noise.
                if (shouldShowDueHint(dueIsApproximate = dueIsApproximate, dueHint = dueHint)) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = dueHint!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Counterparty
                if (counterpartyDisplayName != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = counterpartyDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Status chip — stateColors computed only when chip is drawn. Maps the
                // six spec-aligned action_state keys onto BecalmStateColors tokens. No
                // new theme tokens are introduced in this commit: CANCELLED re-uses the
                // pending tone and relies on the dimmed cardAlpha to read as "closed",
                // and OVERDUE falls back to the shared `dayBadgeOverdue` token.
                if (showChip) {
                    val stateColors: BecalmStateColors = when (normalized) {
                        "PENDING" -> colors.actionStatePending
                        "REMINDED" -> colors.actionStateReminded
                        "FOLLOWED_UP" -> colors.actionStateFollowedUp
                        "COMPLETED" -> colors.actionStateCompleted
                        "OVERDUE" -> colors.dayBadgeOverdue
                        "CANCELLED" -> colors.actionStatePending
                        else -> colors.actionStatePending
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        PillBadge(
                            label = derivedStatus,
                            stateColors = stateColors,
                            horizontalPadding = 8.dp,
                            verticalPadding = 3.dp,
                        )
                    }
                }
            }
        }
    }
}

// ─── Label formatting ─────────────────────────────────────────────────────────

/**
 * Builds the D-N badge label per commitment-management.spec.yml:39-43.
 *
 * Exact grammar:
 *  - `D-0`   same-day exact
 *  - `D-N`   future in N days (N > 0) exact
 *  - `D+N`   overdue by N days (N > 0)
 *  - `D~N`   approximate variant — tilde sits BETWEEN `D` and the count, so a
 *            same-day approximate renders `D~0`, three-days-out approximate
 *            renders `D~3`. Overdue + approximate is not a shape the spec
 *            enumerates; we conservatively prefer the exact overdue form
 *            (`D+N`) since the date is already known to have passed.
 *
 * Kept internal rather than private so focused unit tests in
 * `CommitmentCardFormatterTest` can assert the exact strings without spinning
 * up a Compose host.
 *
 * @param days Signed day-delta of dueAt relative to today-in-KST; 0 = today,
 *             positive = future, negative = overdue.
 * @param approximate True when dueAt was inferred from a fuzzy hint.
 */
internal fun formatDayBadgeLabel(days: Int, approximate: Boolean): String = when {
    days < 0 -> "D+${-days}"
    approximate -> "D~$days"
    else -> "D-$days"
}

/**
 * Visibility gate for the due-hint auxiliary line on [CommitmentCard].
 *
 * Per commitment-management.spec.yml:9,13 the verbatim due-date expression is
 * only displayed alongside *approximate* deadlines — exact deadlines already
 * carry full information via the D-N badge. Extracted so the gating contract
 * can be asserted as a pure unit test without spinning up a Compose host, and
 * so the card site reads as a single boolean rather than an inline compound
 * expression.
 *
 * @return true iff the hint should render, i.e. the deadline is approximate
 *         AND the hint is a non-blank string.
 */
internal fun shouldShowDueHint(dueIsApproximate: Boolean, dueHint: String?): Boolean =
    dueIsApproximate && !dueHint.isNullOrBlank()

/**
 * Pure KST-boundary calculation for the D-N badge. Returns signed day-delta of
 * [dueAt] against the calendar date of [now], both interpreted in the business
 * timezone [zone] (KST for BeCalm — commitment-management.spec.yml:40).
 *
 * Extracted so the boundary contract (KST midnight roll-over, not UTC) can be
 * pinned by pure unit tests without a Compose host: fixing [now] at
 * 2026-04-17T23:30+09:00 and [dueAt] at 2026-04-18T00:00+09:00 MUST return `1`
 * (D-1 tomorrow), whereas the same instants under a UTC zone would misreport
 * `0` (D-0 today).
 *
 * @return `null` iff [dueAt] is `null`; otherwise the integer day-delta.
 */
internal fun daysUntilInKst(dueAt: Instant?, now: Instant, zone: TimeZone): Int? =
    dueAt?.let {
        val today = now.toLocalDateTime(zone).date
        val dueKst = it.toLocalDateTime(zone).date
        today.daysUntil(dueKst)
    }

// ─── Private helpers ──────────────────────────────────────────────────────────

/**
 * D-N 배지와 상태 칩이 padding만 달랐던 중복을 제거하기 위해 추출한 공용 pill.
 * 보존 포인트: fill/border 색, extraSmall corner, labelSmall typography, text color
 * 는 기존 DayBadge/StatusChip과 완전히 동일해야 한다 (spec §5 state color 계약).
 */
@Composable
private fun PillBadge(
    label: String,
    stateColors: BecalmStateColors,
    horizontalPadding: Dp,
    verticalPadding: Dp,
) {
    Box(
        modifier = Modifier
            .background(
                color = stateColors.fill,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .border(
                width = 1.dp,
                color = stateColors.border,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = stateColors.text,
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

/**
 * 네 개의 @Preview에서 반복되던 BecalmTheme + Box(padding=16.dp) 래퍼를 추출.
 * 보존 포인트: padding 16.dp와 BecalmTheme 감싸기는 각 Preview의 렌더링 결과와
 * 동일해야 한다 (Android Studio Preview 비교 기준).
 */
@Composable
private fun PreviewScaffold(content: @Composable () -> Unit) {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Preview
@Composable
private fun PreviewCommitmentCardGivePendingD2() {
    PreviewScaffold {
        CommitmentCard(
            title = "Send contract draft",
            direction = "give",
            derivedStatus = "REMINDED",
            dueAt = Instant.parse("2026-04-18T00:00:00+09:00"),
            counterpartyDisplayName = "Alice Kim",
            onClick = {},
            onMarkDone = {},
        )
    }
}

@Preview
@Composable
private fun PreviewCommitmentCardTakeCompleted() {
    PreviewScaffold {
        CommitmentCard(
            title = "Review budget proposal",
            direction = "take",
            derivedStatus = "COMPLETED",
            dueAt = null,
            counterpartyDisplayName = "Bob Lee",
        )
    }
}

@Preview
@Composable
private fun PreviewCommitmentCardOverdueReminded() {
    PreviewScaffold {
        CommitmentCard(
            title = "Submit expense report",
            direction = "give",
            derivedStatus = "OVERDUE",
            dueAt = Instant.parse("2026-04-10T00:00:00+09:00"),
            counterpartyDisplayName = null,
            dueIsApproximate = true,
            dueHint = "월말",
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun PreviewCommitmentCardNoDueDate() {
    PreviewScaffold {
        CommitmentCard(
            title = "Follow up on proposal",
            direction = "take",
            derivedStatus = "PENDING",
            dueAt = null,
            counterpartyDisplayName = "Carol Park",
        )
    }
}
