package com.becalm.android.worker

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

public interface CallLogPermissionChecker {
    public fun isGranted(): Boolean
}

@Singleton
public class AndroidCallLogPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallLogPermissionChecker {

    override fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class CallLogPermissionModule {

    @Binds
    @Singleton
    public abstract fun bindCallLogPermissionChecker(
        impl: AndroidCallLogPermissionChecker,
    ): CallLogPermissionChecker
}
