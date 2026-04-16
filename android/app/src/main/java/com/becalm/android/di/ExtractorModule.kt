package com.becalm.android.di

import com.becalm.android.domain.extractor.CommitmentExtractor
import com.becalm.android.domain.extractor.GeminiNanoExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds the [CommitmentExtractor] interface to its current implementation.
 *
 * [GeminiNanoExtractor] is the sole implementation (SP-33, KTR-GEMINI-NANO). When the
 * AICore API stabilises this module is the only file that needs to change — all callers
 * depend on [CommitmentExtractor] and are unaffected by the swap.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class ExtractorModule {

    /** Binds [GeminiNanoExtractor] (SP-33) as the singleton [CommitmentExtractor]. */
    @Binds
    @Singleton
    public abstract fun bindCommitmentExtractor(impl: GeminiNanoExtractor): CommitmentExtractor
}
