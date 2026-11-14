package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptainDao {
    @Query("SELECT * FROM captain_profile WHERE id = 1 LIMIT 1")
    fun getCaptainFlow(): Flow<CaptainEntity?>

    @Query("SELECT * FROM captain_profile WHERE id = 1 LIMIT 1")
    suspend fun getCaptain(): CaptainEntity?

    @Query("SELECT * FROM captain_profile WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getCaptainByPhone(phone: String): CaptainEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(captain: CaptainEntity)

    @Query("UPDATE captain_profile SET isOnline = :isOnline WHERE id = 1")
    suspend fun updateOnlineStatus(isOnline: Boolean)

    @Query("UPDATE captain_profile SET isLoggedIn = :isLoggedIn WHERE id = 1")
    suspend fun updateLoginState(isLoggedIn: Boolean)

    @Query("UPDATE captain_profile SET walletBalance = walletBalance + :amount WHERE id = 1")
    suspend fun addToWallet(amount: Double)

    @Query("DELETE FROM captain_profile")
    suspend fun clearAll()
}
