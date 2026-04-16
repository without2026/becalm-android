package com.becalm.android.di

import com.becalm.android.domain.stt.DefaultSttBackendSelector
import com.becalm.android.domain.stt.SttBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds [DefaultSttBackendSelector] as the singleton [SttBackend].
 *
 * [DefaultSttBackendSelector] implements [SttBackend] and internally selects between the
 * Whisper.cpp native engine (SP-34) and [com.becalm.android.domain.stt.AndroidSpeechRecognizerBackend]
 * (SP-35) at call time. Binding it as [SttBackend] lets [com.becalm.android.worker.VoiceTranscriptionWorker]
 * depend only on the interface.
 *
 * Spec coverage: VOI-001.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class SttModule {

    /** Binds [DefaultSttBackendSelector] as the application-scoped [SttBackend]. */
    @Binds
    @Singleton
    public abstract fun bindSttBackend(impl: DefaultSttBackendSelector): SttBackend
}
