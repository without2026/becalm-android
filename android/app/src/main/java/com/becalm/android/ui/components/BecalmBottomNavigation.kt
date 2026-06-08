package com.becalm.android.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.becalm.android.R
import com.becalm.android.ui.navigation.BecalmNavigationDefaults
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.becalmFocusRing
import com.becalm.android.ui.theme.becalmColors
import com.becalm.android.ui.theme.dimens

/**
 * Bottom navigation bar for the three main tabs: People, Schedule, Commitments.
 *
 * Only rendered when [currentRoute] is one of the tab routes (enforced by caller).
 *
 * @param currentRoute  The active route string from the back stack entry.
 * @param navController The nav controller used to navigate between tabs.
 */
@Composable
public fun BecalmBottomNavigation(
    currentRoute: String?,
    navController: NavController,
    personActionBadgeCount: Int = 0,
) {
    val navDividerColor = MaterialTheme.colorScheme.outlineVariant
    val items = listOf(
        BottomNavItem(
            route = BecalmRoute.Persons.path,
            labelRes = R.string.nav_persons,
            icon = Icons.Filled.Group,
            badgeCount = personActionBadgeCount,
        ),
        BottomNavItem(
            route = BecalmRoute.Today.path,
            labelRes = R.string.nav_today,
            icon = Icons.Filled.Today,
        ),
        BottomNavItem(
            route = BecalmRoute.Commitments.path,
            labelRes = R.string.nav_commitments,
            icon = Icons.Filled.Checklist,
        ),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = navDividerColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .background(MaterialTheme.becalmColors.glassPanelFill.copy(alpha = 0.92f))
            .navigationBarsPadding()
            .heightIn(min = MaterialTheme.dimens.bottomNavHeight)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            BottomNavItemButton(
                item = item,
                selected = currentRoute == item.route,
                onClick = { navController.navigateToTab(item.route) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private data class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val badgeCount: Int = 0,
)

@Composable
private fun BottomNavItemButton(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(item.route) { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .becalmFocusRing(shape, interactionSource)
            .semantics {
                role = Role.Button
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(23.dp),
            )
            if (item.badgeCount > 0) {
                BottomNavBadge(
                    count = item.badgeCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-9).dp, y = (-2).dp),
                )
            }
        }
        Text(
            text = stringResource(item.labelRes),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BottomNavBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(min = 16.dp)
            .heightIn(min = 16.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(InkMistWarn)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.testTag("bottom-nav-persons-badge"),
        )
    }
}

private val InkMistWarn = Color(0xFFBC8071)

private fun NavController.navigateToTab(route: String) {
    // Pop to the authenticated home tab rather than the graph's literal start
    // destination, which is Splash and is removed from the back stack by SplashScreen's
    // popUpTo(inclusive = true). Without this, every tab tap would push a new instance
    // because popUpTo cannot find a matching entry and restoreState is skipped.
    navigate(route) {
        popUpTo(BecalmNavigationDefaults.mainTabBackStackRootRoute) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
