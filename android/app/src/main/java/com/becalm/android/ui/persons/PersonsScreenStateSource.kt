package com.becalm.android.ui.persons

import com.becalm.android.data.local.datastore.UserPrefsStore
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

internal class PersonsScreenStateSource @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val projectionPort: PersonsScreenProjectionPort,
) {
    @OptIn(FlowPreview::class)
    fun observe(
        queryFlow: Flow<String>,
        pageSize: Int,
        queryDebounceMs: Long,
    ): Flow<PersonsUiState> = combine(
        userPrefsStore.observeCurrentUserId(),
        projectionPort.observeOfflineStatus(),
        queryFlow.debounce(queryDebounceMs),
    ) { userId, offlineStatus, query ->
        Triple(userId, offlineStatus, query)
    }.flatMapLatest { (userId, offlineStatus, query) ->
        if (userId.isNullOrBlank()) {
            flowOf(
                PersonsUiProjector.unauthenticatedState(
                    query = query,
                    offlineStatus = offlineStatus,
                    pageSize = pageSize,
                ),
            )
        } else {
            combine(
                projectionPort.observePeople(userId),
                projectionPort.observeSearchableContacts(userId),
                projectionPort.observeUnassigned(userId, limit = pageSize),
            ) { peoplePage, searchableContacts, unassigned ->
                PersonsUiProjector.authenticatedState(
                    query = query,
                    pageSize = pageSize,
                    peoplePage = peoplePage,
                    searchableContacts = searchableContacts,
                    unassigned = unassigned,
                    offlineStatus = offlineStatus,
                )
            }
        }
    }
}
