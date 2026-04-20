package com.becalm.android.ui.navigation

/**
 * Central registry of every navigation argument name used across [BecalmRoute].
 *
 * Used wherever a nav argument name must be referenced — e.g. in
 * `navArgument(BecalmNavArgs.PERSON_ID) { ... }` calls inside [BecalmNavHost], or
 * when extracting values from [BackStackEntry.arguments]. This object is the
 * single source of truth for argument keys; the corresponding route classes in
 * [BecalmRoute] embed these keys inline in their `PATH` templates.
 */
public object BecalmNavArgs {

    /**
     * Path argument for `/persons/{person_id}` and
     * `/persons/{person_id}/events/{event_id}`.
     */
    public const val PERSON_ID: String = "person_id"

    /**
     * Path argument for `/persons/{person_id}/events/{event_id}`.
     */
    public const val EVENT_ID: String = "event_id"

    /**
     * Path argument for `/settings/sources/{source_id}`.
     */
    public const val SOURCE_ID: String = "source_id"
}
