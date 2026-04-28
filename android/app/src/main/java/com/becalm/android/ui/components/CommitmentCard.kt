/**
 * SP-48: Glass card rendering a single commitment with direction cast, D-N badge,
 * and status chip.
 *
 * The card reads from design token spec §4 (direction) and §5 (D-N badges, state
 * colors). All user-visible strings come in as parameters — no hardcoded Korean or
 * English copy inside the composable.
 */
package com.becalm.android.ui.components

import com.becalm.android.data.local.db.entity.CommitmentDecisionStatus
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
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
 * @param sourceContextLabel Optional display-safe source context prepared by the
 *                      caller from existing `source_event_*` fields. This composable
 *                      only renders it and does not resolve source metadata.
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
    itemType: String,
    title: String,
    direction: String?,
    scheduleStatus: String?,
    decisionStatus: String?,
    derivedStatus: String?,
    dueAt: Instant?,
    counterpartyDisplayName: String?,
    modifier: Modifier = Modifier,
    dueIsApproximate: Boolean = false,
    dueHint: String? = null,
    isManual: Boolean = false,
    sourceContextLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onMarkDone: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme
    val normalizedItemType = itemType.lowercase()
    val normalizedDirection = direction?.lowercase()
    val normalizedScheduleStatus = scheduleStatus?.lowercase()
    val normalizedDecisionStatus = decisionStatus?.lowercase()

    // Direction stripe color
    val stripeColor = when (normalizedDirection) {
        "give" -> colors.directionGive.border
        "take" -> colors.directionTake.border
        else -> colorScheme.outline
    }

    // Status → alpha; chip visibility. COMPLETED and CANCELLED both dim per spec
    // CMT-009 — the user has closed the row one way or the other. OVERDUE stays at
    // full alpha so it keeps pulling the user's attention (they still need to act).
    val normalized = derivedStatus?.uppercase().orEmpty()
    val isCompleted = normalized == "COMPLETED"
    val isCancelled = normalized == "CANCELLED"
    val isTerminal = isCompleted || isCancelled
    val cardAlpha = if (isTerminal) 0.6f else 1.0f
    // Chip is always shown so the terminal-state reason is visible even when dimmed.
    val showChip = normalizedItemType == CommitmentItemType.ACTION && normalized.isNotBlank()
    val itemTypeLabel = remember(normalizedItemType) {
        when (normalizedItemType) {
            CommitmentItemType.ACTION -> R.string.commitment_item_type_action
            CommitmentItemType.SCHEDULE -> R.string.commitment_item_type_schedule
            CommitmentItemType.DECISION -> R.string.commitment_item_type_decision
            else -> R.string.commitment_item_type_action
        }
    }
    val subtypeLabel = when (normalizedItemType) {
        CommitmentItemType.ACTION -> when (normalizedDirection) {
            "give" -> stringResource(R.string.commitments_filter_give)
            "take" -> stringResource(R.string.commitments_filter_take)
            else -> null
        }
        CommitmentItemType.SCHEDULE -> when (normalizedScheduleStatus) {
            CommitmentScheduleStatus.CONFIRMED -> stringResource(R.string.commitment_subtype_schedule_confirmed)
            CommitmentScheduleStatus.CHANGED -> stringResource(R.string.commitment_subtype_schedule_changed)
            CommitmentScheduleStatus.POSTPONED -> stringResource(R.string.commitment_subtype_schedule_postponed)
            CommitmentScheduleStatus.CANCELLED -> stringResource(R.string.commitment_subtype_schedule_cancelled)
            CommitmentScheduleStatus.FOLLOW_UP -> stringResource(R.string.commitment_subtype_schedule_follow_up)
            else -> null
        }
        CommitmentItemType.DECISION -> when (normalizedDecisionStatus) {
            CommitmentDecisionStatus.APPROVED -> stringResource(R.string.commitment_subtype_decision_approved)
            CommitmentDecisionStatus.REJECTED -> stringResource(R.string.commitment_subtype_decision_rejected)
            CommitmentDecisionStatus.CHOSEN -> stringResource(R.string.commitment_subtype_decision_chosen)
            CommitmentDecisionStatus.DEFERRED -> stringResource(R.string.commitment_subtype_decision_deferred)
            CommitmentDecisionStatus.ONGOING -> stringResource(R.string.commitment_subtype_decision_ongoing)
            else -> null
        }
        else -> null
    }

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
                // Prototype-aligned hierarchy: person/context first, then promise text.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    PersonContext(
                        name = counterpartyDisplayName ?: stringResource(R.string.commitment_counterparty_unknown),
                        sourceContextLabel = sourceContextLabel,
                        modifier = Modifier.weight(1f),
                    )
                    if (isManual) {
                        Spacer(modifier = Modifier.width(8.dp))
                        PillBadge(
                            label = stringResource(R.string.commitment_manual_badge),
                            stateColors = colors.actionStatePending,
                            horizontalPadding = 6.dp,
                            verticalPadding = 2.dp,
                        )
                    }
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
                    if (showMarkDone) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = requireNotNull(onMarkDone),
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

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                if (shouldShowDueHint(dueIsApproximate = dueIsApproximate, dueHint = dueHint)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dueHint!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillBadge(
                        label = stringResource(itemTypeLabel),
                        stateColors = colors.actionStatePending,
                        horizontalPadding = 6.dp,
                        verticalPadding = 2.dp,
                    )
                    if (!subtypeLabel.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        PillBadge(
                            label = subtypeLabel,
                            stateColors = colors.actionStateFollowedUp,
                            horizontalPadding = 6.dp,
                            verticalPadding = 2.dp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (showChip) {
                        PillBadge(
                            label = statusLabel(normalized),
                            stateColors = statusColors(normalized),
                            horizontalPadding = 8.dp,
                            verticalPadding = 3.dp,
                        )
                    }
                }

            }
        }
    }
}

// ─── Card hierarchy helpers ──────────────────────────────────────────────────

@Composable
private fun PersonContext(
    name: String,
    sourceContextLabel: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sourceContextLabel.isNullOrBlank()) {
                Text(
                    text = sourceContextLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun statusLabel(normalized: String): String = when (normalized) {
    "PENDING" -> stringResource(R.string.commitment_state_pending)
    "REMINDED" -> stringResource(R.string.commitment_state_reminded)
    "FOLLOWED_UP" -> stringResource(R.string.commitment_state_followed_up)
    "COMPLETED" -> stringResource(R.string.commitment_state_completed)
    "OVERDUE" -> stringResource(R.string.commitment_state_overdue)
    "CANCELLED" -> stringResource(R.string.commitment_state_cancelled)
    else -> normalized
}

@Composable
private fun statusColors(normalized: String): BecalmStateColors {
    val colors = MaterialTheme.becalmColors
    return when (normalized) {
        "PENDING" -> colors.actionStatePending
        "REMINDED" -> colors.actionStateReminded
        "FOLLOWED_UP" -> colors.actionStateFollowedUp
        "COMPLETED" -> colors.actionStateCompleted
        "OVERDUE" -> colors.dayBadgeOverdue
        "CANCELLED" -> colors.actionStatePending
        else -> colors.actionStatePending
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
            itemType = CommitmentItemType.ACTION,
            title = "Send contract draft",
            direction = "give",
            scheduleStatus = null,
            decisionStatus = null,
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
            itemType = CommitmentItemType.ACTION,
            title = "Review budget proposal",
            direction = "take",
            scheduleStatus = null,
            decisionStatus = null,
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
            itemType = CommitmentItemType.ACTION,
            title = "Submit expense report",
            direction = "give",
            scheduleStatus = null,
            decisionStatus = null,
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
            itemType = CommitmentItemType.ACTION,
            title = "Follow up on proposal",
            direction = "take",
            scheduleStatus = null,
            decisionStatus = null,
            derivedStatus = "PENDING",
            dueAt = null,
            counterpartyDisplayName = "Carol Park",
        )
    }
}
