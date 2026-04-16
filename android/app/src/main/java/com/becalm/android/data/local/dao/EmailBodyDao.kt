package com.becalm.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.entities.EmailBody

// spec: ING-006..ING-008 — email bodies, local only

@Dao
interface EmailBodyDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(emailBody: EmailBody): Long

    @Query("SELECT * FROM email_bodies WHERE raw_ingestion_id = :rawIngestionId")
    suspend fun getByRawIngestionId(rawIngestionId: String): EmailBody?

    @Query("SELECT COUNT(*) FROM email_bodies")
    suspend fun count(): Int
}
