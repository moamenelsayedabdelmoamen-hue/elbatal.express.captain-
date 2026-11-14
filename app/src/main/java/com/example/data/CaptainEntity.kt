package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "captain_profile")
data class CaptainEntity(
    @PrimaryKey val id: Int = 1,
    val uid: String = "",
    val fullName: String,
    val phoneNumber: String,
    val email: String = "",
    val password: String = "",
    val avatarUri: String = "",
    val avatarPresetIndex: Int = 0,
    val nationalIdUrl: String = "",
    val licenseUrl: String = "",
    val profileImagePath: String = "",
    val idFrontImagePath: String = "",
    val idBackImagePath: String = "",
    val licenseImagePath: String = "",
    val role: String = "captain",
    val status: String = "active",
    val vehicleType: String = "سكوتر / دراجة نارية",
    val licensePlate: String = "أ ب ج 1 2 3",
    val rating: Float = 5.0f,
    val totalOrdersCount: Int = 0,
    val walletBalance: Double = 0.0,
    val isOnline: Boolean = true,
    val isLoggedIn: Boolean = false
) {
    val effectiveProfileImage: String
        get() = profileImagePath.ifBlank { avatarUri }

    val effectiveIdFrontImage: String
        get() = idFrontImagePath.ifBlank { nationalIdUrl }

    val effectiveIdBackImage: String
        get() = idBackImagePath

    val effectiveLicenseImage: String
        get() = licenseImagePath.ifBlank { licenseUrl }

    val isNationalIdComplete: Boolean
        get() = effectiveIdFrontImage.isNotBlank() && effectiveIdBackImage.isNotBlank()
}
