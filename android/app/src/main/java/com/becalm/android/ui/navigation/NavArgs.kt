package com.becalm.android.ui.navigation

/**
 * Central registry of every navigation argument name used across [BecalmRoute].
 *
 * Use these constants wherever a nav argument name must be referenced outside of
 * the route companion objects — e.g. in `navArgument(BecalmNavArgs.PERSON_ID) { ... }`
 * calls inside [BecalmNavHost], or when extracting values from [BackStackEntry.arguments].
 *
 * Each constant mirrors the `ARG_*` sibling declared on the corresponding route's
 * companion object; both must be kept in sync.
 */
public object BecalmNavArgs {

    /**
     * Path argument for `/persons/{person_id}` and
     * `/persons/{person_id}/events/{event_id}`.
     * Mirrors [BecalmRoute.PersonDetail.ARG_PERSON_ID] and
     * [BecalmRoute.RawEventDetail.ARG_PERSON_ID].
     */
    public const val PERSON_ID: String = "person_id"

    /**
     * Path argument for `/persons/{person_id}/events/{event_id}`.
     * Mirrors [BecalmRoute.RawEventDetail.ARG_EVENT_ID].
     */
    public const val EVENT_ID: String = "event_id"

    /**
     * Path argument for `/settings/sources/{source_id}`.
     * Mirrors [BecalmRoute.SourceDetail.ARG_SOURCE_ID].
     */
    public const val SOURCE_ID: String = "source_id"
}
