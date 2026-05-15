package com.becalm.android.core.observability

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

public interface FirebaseCrashlyticsPort {
    public fun setCollectionEnabled(enabled: Boolean)
    public fun setUserId(userId: String)
    public fun setCustomKey(key: String, value: String)
    public fun log(message: String)
    public fun recordException(throwable: Throwable)
}

@Singleton
public class RealFirebaseCrashlyticsPort @Inject constructor(
    @ApplicationContext private val context: Context,
) : FirebaseCrashlyticsPort {

    private val crashlytics: FirebaseCrashlytics?
        get() = if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseCrashlytics.getInstance() else null

    override fun setCollectionEnabled(enabled: Boolean) {
        crashlytics?.setCrashlyticsCollectionEnabled(enabled)
    }

    override fun setUserId(userId: String) {
        crashlytics?.setUserId(userId)
    }

    override fun setCustomKey(key: String, value: String) {
        crashlytics?.setCustomKey(key, value)
    }

    override fun log(message: String) {
        crashlytics?.log(message)
    }

    override fun recordException(throwable: Throwable) {
        crashlytics?.recordException(throwable)
    }
}
