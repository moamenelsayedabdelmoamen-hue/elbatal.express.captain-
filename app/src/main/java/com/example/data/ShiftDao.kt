package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {
    @Query("SELECT * FROM captain_shifts WHERE captainUid = :captainUid ORDER BY dayOfWeek ASC, startTime ASC, id ASC")
    fun getShiftsForCaptainFlow(captainUid: String): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM captain_shifts ORDER BY dayOfWeek ASC, startTime ASC, id ASC")
    fun getAllShiftsFlow(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM captain_shifts WHERE captainUid = :captainUid AND dayOfWeek = :dayOfWeek ORDER BY startTime ASC, id ASC")
    suspend fun getShiftsForDay(captainUid: String, dayOfWeek: Int): List<ShiftEntity>

    @Query("SELECT * FROM captain_shifts WHERE captainUid = :captainUid AND dayOfWeek = :dayOfWeek ORDER BY startTime ASC, id ASC LIMIT 1")
    suspend fun getShiftForDay(captainUid: String, dayOfWeek: Int): ShiftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShifts(shifts: List<ShiftEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShift(shift: ShiftEntity)

    @Query("DELETE FROM captain_shifts WHERE captainUid = :captainUid OR captainUid = ''")
    suspend fun clearShiftsForCaptain(captainUid: String)

    @Query("DELETE FROM captain_shifts")
    suspend fun clearAll()

    /**
     * Atomically replaces the captain's shifts in local Room DB within a single transaction.
     * Ensures atomic removal of old/deleted shifts and insertion of new shifts without duplicates.
     */
    @Transaction
    suspend fun atomicReplaceCaptainShifts(captainUid: String, newShifts: List<ShiftEntity>) {
        clearShiftsForCaptain(captainUid)
        if (newShifts.isNotEmpty()) {
            insertShifts(newShifts)
        }
    }
}
