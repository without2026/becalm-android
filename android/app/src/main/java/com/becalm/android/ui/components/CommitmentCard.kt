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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.BecalmStateColors
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors
import com.becalm.android.ui.theme.glassPanel
import java.time.LocalDate as JLocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.datetime.LocalDate

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
 *                      `"DRAFT"`, `"CONFIRMED"`, `"SCHEDULED"`, `"DONE"`, `"DISMISSED"`.
 *                      Unknown values map to pending styling.
 * @param dueDate       Optional due date for the D-N badge. `null` = no badge shown.
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
 *                      is only rendered when non-null AND [derivedStatus] is neither
 *                      `"DONE"` nor `"DISMISSED"`.
 */
@Composable
public fun CommitmentCard(
    title: String,
    direction: String,
    derivedStatus: String,
    dueDate: LocalDate?,
    counterpartyDisplayName: String?,
    modifier: Modifier = Modifier,
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

    // Status → alpha; chip visibility
    val isDismissed = derivedStatus.uppercase() == "DISMISSED"
    val isDone = derivedStatus.uppercase() == "DONE"
    val cardAlpha = if (isDismissed || isDone) 0.55f else 1.0f
    val showChip = !isDismissed

    // D-N badge — single date computation, memoised on dueDate
    val daysUntil: Int? = remember(dueDate) {
        dueDate?.let {
            val today = JLocalDate.now(ZoneOffset.UTC)
            val jDate = JLocalDate.of(it.year, it.monthNumber, it.dayOfMonth)
            ChronoUnit.DAYS.between(today, jDate).toInt()
        }
    }
    val badgeColors: BecalmStateColors? = daysUntil?.let {
        when {
            it == 0 -> colors.dayBadgeToday
            it in 1..3 -> colors.dayBadgeSoon
            it >= 4 -> colors.dayBadgeUpcoming
            else -> colors.dayBadgeOverdue // negative = past due
        }
    }
    val badgeLabel: String? = daysUntil?.let {
        if (it >= 0) "D-$it" else "D+${-it}"
    }

    val semanticsDesc = "$direction $title"
    val showMarkDone = onMarkDone != null && !isDone && !isDismissed

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
                    // D-N badge
                    if (badgeColors != null && badgeLabel != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        DayBadge(label = badgeLabel, stateColors = badgeColors)
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

                // Counterparty
                if (counterpartyDisplayName != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = counterpartyDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Status chip — stateColors computed only when chip is drawn
                if (showChip) {
                    val stateColors: BecalmStateColors = when (derivedStatus.uppercase()) {
                        "DRAFT", "CONFIRMED" -> colors.actionStatePending
                        "SCHEDULED" -> colors.actionStateReminded
                        "DONE" -> colors.actionStateCompleted
                        else -> colors.actionStatePending
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        StatusChip(stateColors = stateColors, label = derivedStatus)
                    }
                }
            }
        }
    }
}

// ─── Private helpers ──────────────────────────────────────────────────────────

@Composable
private fun DayBadge(
    label: String,
    stateColors: BecalmStateColors,
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
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = stateColors.text,
        )
    }
}

@Composable
private fun StatusChip(
    stateColors: BecalmStateColors,
    label: String,
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
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = stateColors.text,
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview
@Composable
private fun PreviewCommitmentCardGivePendingD2() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CommitmentCard(
                title = "Send contract draft",
                direction = "give",
                derivedStatus = "CONFIRMED",
                dueDate = LocalDate(2026, 4, 18),
                counterpartyDisplayName = "Alice Kim",
                onClick = {},
                onMarkDone = {},
            )
        }
    }
}

@Preview
@Composable
private fun PreviewCommitmentCardTakeCompleted() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CommitmentCard(
                title = "Review budget proposal",
                direction = "take",
                derivedStatus = "DONE",
                dueDate = null,
                counterpartyDisplayName = "Bob Lee",
            )
        }
    }
}

@Preview
@Composable
private fun PreviewCommitmentCardOverdueReminded() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CommitmentCard(
                title = "Submit expense report",
                direction = "give",
                derivedStatus = "SCHEDULED",
                dueDate = LocalDate(2026, 4, 10),
                counterpartyDisplayName = null,
                onClick = {},
            )
        }
    }
}

@Preview
@Composable
private fun PreviewCommitmentCardNoDueDate() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CommitmentCard(
                title = "Follow up on proposal",
                direction = "take",
                derivedStatus = "DRAFT",
                dueDate = null,
                counterpartyDisplayName = "Carol Park",
            )
        }
    }
}
