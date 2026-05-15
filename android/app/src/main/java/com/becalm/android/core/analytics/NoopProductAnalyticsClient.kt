package com.becalm.android.core.analytics

import javax.inject.Inject

public class NoopProductAnalyticsClient @Inject constructor() : ProductAnalyticsClient {
    override fun track(event: ProductAnalyticsEvent): Unit = Unit
    override fun setUserScope(userId: String?): Unit = Unit
    override fun resetUserScope(): Unit = Unit
}
