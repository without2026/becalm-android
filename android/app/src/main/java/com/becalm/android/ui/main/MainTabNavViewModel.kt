package com.becalm.android.ui.main

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.repository.PersonActionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
public data class MainTabNavState(
    val personActionBadgeCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class MainTabNavViewModel @Inject constructor(
    userPrefsStore: UserPrefsStore,
    private val personActionRepository: PersonActionRepository,
) : ViewModel() {
    public val state: StateFlow<MainTabNavState> = userPrefsStore.observeCurrentUserId()
        .flatMapLatest { userId ->
            if (userId.isNullOrBlank()) {
                flowOf(MainTabNavState())
            } else {
                personActionRepository
                    .observeActiveForSurface(
                        userId = userId,
                        surface = PERSON_ACTION_NAV_SURFACE,
                        limit = PERSON_ACTION_NAV_BADGE_QUERY_LIMIT,
                    )
                    .map { rows ->
                        MainTabNavState(
                            personActionBadgeCount = urgentPersonActionBadgeCount(rows),
                        )
                    }
                    .catch { emit(MainTabNavState()) }
            }
        }
        .catch { emit(MainTabNavState()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainTabNavState(),
        )
}

internal const val PERSON_ACTION_NAV_SURFACE: String = "person"
internal const val PERSON_ACTION_NAV_BADGE_QUERY_LIMIT: Int = 10
internal const val PERSON_ACTION_NAV_BADGE_OVERFLOW_COUNT: Int = 10

internal fun urgentPersonActionBadgeCount(rows: List<PersonActionItemCacheEntity>): Int =
    rows.count { it.isUrgentForPersonNav() }
        .coerceAtMost(PERSON_ACTION_NAV_BADGE_OVERFLOW_COUNT)

private fun PersonActionItemCacheEntity.isUrgentForPersonNav(): Boolean {
    val score = urgencyScore
    return score >= 60.0 || (score in 0.0..1.0 && score >= 0.6)
}
