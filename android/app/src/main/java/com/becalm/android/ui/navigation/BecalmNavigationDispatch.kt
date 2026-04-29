package com.becalm.android.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.navigation.NavHostController
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.commitments.CommitmentManagementNavigation
import com.becalm.android.ui.sources.ContactsSourceDetailEffect
import com.becalm.android.ui.sources.SourceDetailEffect
import com.becalm.android.ui.sources.SourceReconnectDestination
import com.becalm.android.ui.sources.SourcesListNavigation
import com.becalm.android.ui.today.TodayEffect

private const val SOURCE_RECONNECT_RETURN_KEY = "source_reconnect_return"

internal fun NavHostController.dispatchTodayEffect(effect: TodayEffect) {
    when (effect) {
        TodayEffect.NavigateToSettings -> navigate(BecalmRoute.Settings.path)
    }
}

internal fun NavHostController.dispatchSourcesListNavigation(target: SourcesListNavigation) {
    when (target) {
        is SourcesListNavigation.SourceDetail -> navigate(BecalmRoute.SourceDetail(target.sourceType).path)
        SourcesListNavigation.ContactsPermission -> navigate(BecalmRoute.OnboardingContacts.path)
        SourcesListNavigation.ContactsDetail -> navigate(BecalmRoute.ContactsSourceDetail.path)
    }
}

internal fun NavHostController.dispatchSourceDetailEffect(effect: SourceDetailEffect) {
    when (effect) {
        is SourceDetailEffect.OpenReconnect -> {
            currentBackStackEntry?.savedStateHandle?.set(SOURCE_RECONNECT_RETURN_KEY, true)
            navigate(
                when (effect.destination) {
                SourceReconnectDestination.RECORDING_FOLDER -> BecalmRoute.OnboardingRecordingFolder.path
                SourceReconnectDestination.GMAIL -> BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.GMAIL.storageKey).path
                SourceReconnectDestination.OUTLOOK_MAIL -> BecalmRoute.OnboardingEmailPipa(
                    EmailPipaProvider.OUTLOOK_MAIL.storageKey,
                ).path
                SourceReconnectDestination.IMAP -> BecalmRoute.OnboardingEmailPipa("imap").path
                SourceReconnectDestination.GOOGLE_CALENDAR -> BecalmRoute.OnboardingGoogleCalendar.path
                SourceReconnectDestination.OUTLOOK_CALENDAR -> BecalmRoute.OnboardingOutlookCalendar.path
                },
            )
        }
    }
}

internal fun NavHostController.navigateAfterSourceReconnectOr(route: String) {
    val previousHandle = previousBackStackEntry?.savedStateHandle
    if (previousHandle?.get<Boolean>(SOURCE_RECONNECT_RETURN_KEY) == true) {
        previousHandle.remove<Boolean>(SOURCE_RECONNECT_RETURN_KEY)
        if (popBackStack()) return
    }
    navigate(route)
}

internal fun dispatchCommitmentManagementNavigation(
    navigation: CommitmentManagementNavigation,
    onOpenDetail: (String) -> Unit,
) {
    when (navigation) {
        is CommitmentManagementNavigation.OpenDetail -> onOpenDetail(navigation.commitmentId)
    }
}

internal fun NavHostController.navigateAfterSignOut() {
    navigate(BecalmRoute.Splash.path) {
        popUpTo(0) { inclusive = true }
    }
}

internal fun dispatchContactsSourceDetailEffect(
    effect: ContactsSourceDetailEffect,
    navController: NavHostController,
    context: Context,
) {
    when (effect) {
        ContactsSourceDetailEffect.OpenPermissionSettings -> {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
            context.startActivity(intent)
        }
        ContactsSourceDetailEffect.OpenContactsPermissionScreen -> {
            navController.navigate(BecalmRoute.OnboardingContacts.path)
        }
    }
}
