package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.ironpath.data.local.entity.AccountDeletionDraftEntity
import com.example.ironpath.data.local.entity.AccountDeletionJournal

@Dao
interface AccountDeletionDao {
    @Query("SELECT * FROM account_deletion_journal WHERE id = 1")
    suspend fun getJournal(): AccountDeletionJournal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(journal: AccountDeletionJournal)

    @Update suspend fun update(journal: AccountDeletionJournal)

    @Query("SELECT * FROM account_deletion_draft WHERE id = 1")
    suspend fun getDraft(): AccountDeletionDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: AccountDeletionDraftEntity)

    @Query("DELETE FROM account_deletion_draft WHERE operationId = :operationId")
    suspend fun deleteDraft(operationId: String)
}
