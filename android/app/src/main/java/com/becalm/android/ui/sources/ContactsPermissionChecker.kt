package com.becalm.android.ui.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Query seam for the current READ_CONTACTS runtime-permission state.
 *
 * Kept behind an interface so sources unit tests can drive the contacts row
 * navigation contract without depending on Android framework state.
 */
public interface ContactsPermissionChecker {
    /** Returns `true` when READ_CONTACTS is currently granted to the app. */
    public fun isGranted(): Boolean

    /**
     * Observable grant-state seam for UI projection.
     *
     * Production currently emits the latest snapshot only; tests may supply a hot flow
     * to exercise permission-driven transitions deterministically.
     */
    public fun observeGrantState(): Flow<Boolean> = flowOf(isGranted())
}

@Singleton
public class AndroidContactsPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactsPermissionChecker {

    override fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class ContactsPermissionModule {

    @Binds
    @Singleton
    public abstract fun bindContactsPermissionChecker(
        impl: AndroidContactsPermissionChecker,
    ): ContactsPermissionChecker
}
