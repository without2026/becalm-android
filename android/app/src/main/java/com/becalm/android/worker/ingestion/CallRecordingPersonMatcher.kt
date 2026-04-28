package com.becalm.android.worker.ingestion

import android.content.Context
import android.provider.CallLog
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.PhoneNumberUtils
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.worker.CallLogPermissionChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

public data class CallRecordingPersonMatch(
    val personRef: String,
    val confidence: Int,
)

public interface CallRecordingPersonMatcher {
    public suspend fun match(
        recordingDateAddedSec: Long,
        recordingDurationSec: Int,
        displayName: String,
    ): CallRecordingPersonMatch?
}

public object NoOpCallRecordingPersonMatcher : CallRecordingPersonMatcher {
    override suspend fun match(
        recordingDateAddedSec: Long,
        recordingDurationSec: Int,
        displayName: String,
    ): CallRecordingPersonMatch? = null
}

@Singleton
public class CallLogCallRecordingPersonMatcher @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userPrefsStore: UserPrefsStore,
    private val callLogPermissionChecker: CallLogPermissionChecker,
    private val logger: Logger,
) : CallRecordingPersonMatcher {

    override suspend fun match(
        recordingDateAddedSec: Long,
        recordingDurationSec: Int,
        displayName: String,
    ): CallRecordingPersonMatch? {
        val consented = userPrefsStore.observeCallLogMatchingConsent().first()
        val permissionGranted = callLogPermissionChecker.isGranted()
        if (!consented || !permissionGranted) {
            logger.d(TAG, "calllog match skipped consent=$consented permission=$permissionGranted")
            return null
        }

        val recordingAddedMs = recordingDateAddedSec * MILLIS_PER_SECOND
        val recordingDurationMs = recordingDurationSec.coerceAtLeast(0) * MILLIS_PER_SECOND
        val queryStartMs = recordingAddedMs - max(recordingDurationMs + TEN_MINUTES_MS, THIRTY_MINUTES_MS)
        val queryEndMs = recordingAddedMs + TEN_MINUTES_MS
        val filenamePersonRef = PhoneNumberUtils.extractCounterpartyNumberFromDisplayName(displayName)

        val best = queryCandidates(queryStartMs, queryEndMs).maxByOrNull { candidate ->
            score(
                candidate = candidate,
                recordingAddedMs = recordingAddedMs,
                recordingDurationSec = recordingDurationSec,
                filenamePersonRef = filenamePersonRef,
            )
        } ?: return null

        val confidence = score(
            candidate = best,
            recordingAddedMs = recordingAddedMs,
            recordingDurationSec = recordingDurationSec,
            filenamePersonRef = filenamePersonRef,
        )
        if (confidence < MATCH_THRESHOLD) {
            logger.d(
                TAG,
                "calllog match below threshold nameHash=${redact(displayName)} confidence=$confidence",
            )
            return null
        }

        logger.d(
            TAG,
            "calllog match accepted nameHash=${redact(displayName)} confidence=$confidence",
        )
        return CallRecordingPersonMatch(personRef = best.personRef, confidence = confidence)
    }

    private fun queryCandidates(startMs: Long, endMs: Long): List<CallLogCandidate> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        val selection = "${CallLog.Calls.DATE} BETWEEN ? AND ? AND ${CallLog.Calls.DURATION} > ?"
        val selectionArgs = arrayOf(startMs.toString(), endMs.toString(), "0")
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        return try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idxNumber = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val idxDate = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val idxDuration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val idxType = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        val personRef = PhoneNumberUtils.toE164OrNull(cursor.getString(idxNumber)) ?: continue
                        add(
                            CallLogCandidate(
                                personRef = personRef,
                                dateMs = cursor.getLong(idxDate),
                                durationSec = cursor.getLong(idxDuration).toInt(),
                                type = cursor.getInt(idxType),
                            ),
                        )
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.w(TAG, "calllog query failed; falling back to filename matching", e)
            emptyList()
        }
    }

    private fun score(
        candidate: CallLogCandidate,
        recordingAddedMs: Long,
        recordingDurationSec: Int,
        filenamePersonRef: String?,
    ): Int {
        val callEndMs = candidate.dateMs + candidate.durationSec.coerceAtLeast(0) * MILLIS_PER_SECOND
        val nearestTimeDeltaMs = minOf(
            abs(recordingAddedMs - callEndMs),
            abs(recordingAddedMs - candidate.dateMs),
        )
        val durationDeltaSec = abs(recordingDurationSec - candidate.durationSec)

        var score = when {
            nearestTimeDeltaMs <= 30_000L -> 50
            nearestTimeDeltaMs <= 120_000L -> 40
            nearestTimeDeltaMs <= 300_000L -> 30
            nearestTimeDeltaMs <= TEN_MINUTES_MS -> 15
            else -> 0
        }
        score += when {
            durationDeltaSec <= 5 -> 35
            durationDeltaSec <= 15 -> 25
            durationDeltaSec <= 30 -> 15
            durationDeltaSec <= 60 -> 5
            else -> 0
        }
        score += when {
            filenamePersonRef == null -> 0
            filenamePersonRef == candidate.personRef -> 20
            else -> -40
        }
        if (candidate.type == CallLog.Calls.INCOMING_TYPE || candidate.type == CallLog.Calls.OUTGOING_TYPE) {
            score += 5
        }
        return score.coerceIn(0, 100)
    }

    private data class CallLogCandidate(
        val personRef: String,
        val dateMs: Long,
        val durationSec: Int,
        val type: Int,
    )

    private companion object {
        const val TAG: String = "CallLogMatcher"
        const val MATCH_THRESHOLD: Int = 60
        const val MILLIS_PER_SECOND: Long = 1_000L
        const val TEN_MINUTES_MS: Long = 10 * 60 * MILLIS_PER_SECOND
        const val THIRTY_MINUTES_MS: Long = 30 * 60 * MILLIS_PER_SECOND
    }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class CallRecordingPersonMatcherModule {

    @Binds
    @Singleton
    public abstract fun bindCallRecordingPersonMatcher(
        impl: CallLogCallRecordingPersonMatcher,
    ): CallRecordingPersonMatcher
}
