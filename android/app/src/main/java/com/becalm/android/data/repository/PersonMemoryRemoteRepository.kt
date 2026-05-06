package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.PersonMemoryUploadRequestDto
import com.becalm.android.domain.person.PersonMemoryHash
import com.becalm.android.domain.person.PersonMemoryMarkdownValidator
import com.becalm.android.domain.person.PersonMemoryPathResolver
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import retrofit2.Response

public data class PersonMemoryRemoteMirror(
    val bucket: String,
    val objectPath: String,
    val personId: String,
    val contentHash: String,
    val generatedAt: Instant,
)

public interface PersonMemoryRemoteRepository {
    public suspend fun uploadLocalMemory(userId: String, personId: String): BecalmResult<PersonMemoryRemoteMirror>
}

@Singleton
public class PersonMemoryRemoteRepositoryImpl @Inject constructor(
    private val apiProvider: Provider<RailwayApi>,
    private val memoryStore: PersonMemoryStore,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PersonMemoryRemoteRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        api: RailwayApi,
        memoryStore: PersonMemoryStore,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        apiProvider = Provider { api },
        memoryStore = memoryStore,
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun uploadLocalMemory(
        userId: String,
        personId: String,
    ): BecalmResult<PersonMemoryRemoteMirror> = withContext(ioDispatcher) {
        val relativePath = PersonMemoryPathResolver.localRelativePath(userId, personId)
        val markdown = try {
            memoryStore.readText(relativePath)
        } catch (e: IOException) {
            return@withContext BecalmResult.Failure(BecalmError.Io(e.message ?: "memory read failed"))
        }
        if (markdown == null) {
            return@withContext BecalmResult.Failure(BecalmError.NotFound("person_memory"))
        }

        val validation = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = userId,
            expectedPersonId = personId,
        )
        if (validation.errors.isNotEmpty()) {
            return@withContext BecalmResult.Failure(
                BecalmError.Validation(
                    field = "content_markdown",
                    message = validation.errors.joinToString(),
                ),
            )
        }
        val generatedAt = markdown.frontmatterValue("generated_at")
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Validation("generated_at", "generated_at frontmatter is required"),
            )
        val request = PersonMemoryUploadRequestDto(
            schemaVersion = 1,
            contentMarkdown = markdown,
            contentHash = PersonMemoryHash.sha256(markdown),
            generatedAt = generatedAt,
        )

        val response = try {
            api.uploadPersonMemory(personId = personId, request = request)
        } catch (e: IOException) {
            return@withContext BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
        }

        if (!response.isSuccessful) {
            logger.w(TAG, "uploadLocalMemory HTTP ${response.code()} personId=$personId")
            return@withContext BecalmResult.Failure(response.toError())
        }
        val body = response.body()
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("person memory upload returned empty body")),
            )
        BecalmResult.Success(
            PersonMemoryRemoteMirror(
                bucket = body.bucket,
                objectPath = body.objectPath,
                personId = body.personId,
                contentHash = body.contentHash,
                generatedAt = body.generatedAt,
            ),
        )
    }

    private fun String.frontmatterValue(key: String): String? =
        lineSequence()
            .firstOrNull { line -> line.trimStart().startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }

    private fun <T> Response<T>.toError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("person_memory")
        422 -> BecalmError.Validation("content_markdown", errorBody()?.string() ?: "validation failed")
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    private companion object {
        private const val TAG = "PersonMemoryRemoteRepo"
    }
}
