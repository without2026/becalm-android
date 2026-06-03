package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(
    tableName = "person_action_mutation_queue",
    indices = [
        Index(value = ["user_id", "sync_status", "updated_at"], name = "idx_person_action_mutation_queue_user_sync"),
        Index(value = ["user_id", "client_mutation_id"], unique = true, name = "ux_person_action_mutation_queue_user_mutation"),
    ],
)
public data class PersonActionMutationQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "action_item_id")
    val actionItemId: String,
    @ColumnInfo(name = "client_mutation_id")
    val clientMutationId: String,
    @ColumnInfo(name = "mutation_kind")
    val mutationKind: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
    @ColumnInfo(name = "last_error_client_action")
    val lastErrorClientAction: String?,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Instant?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
