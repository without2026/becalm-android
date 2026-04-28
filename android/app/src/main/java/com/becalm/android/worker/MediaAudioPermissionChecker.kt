package com.becalm.android.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

public interface MediaAudioPermissionChecker {
    public fun isGranted(): Boolean
}

@Singleton
public class AndroidMediaAudioPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaAudioPermissionChecker {

    override fun isGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class MediaAudioPermissionModule {

    @Binds
    @Singleton
    public abstract fun bindMediaAudioPermissionChecker(
        impl: AndroidMediaAudioPermissionChecker,
    ): MediaAudioPermissionChecker

    @Binds
    @Singleton
    public abstract fun bindRuntimeSyncSourceResolver(
        impl: DefaultRuntimeSyncSourceResolver,
    ): RuntimeSyncSourceResolver
}
