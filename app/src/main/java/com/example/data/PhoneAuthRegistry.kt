package com.example.data

import android.content.Context

/**
 * Handles phone number normalization (stripping invisible Unicode bidi markers,
 * converting Arabic/Persian digits, normalizing Egyptian +20 / 0020 / 01X formats)
 * and maintains a persistent local phone-to-email lookup directory on device
 * so phone login works instantaneously even across logouts or strict Firestore rules.
 */
object PhoneAuthRegistry {
    private const val PREFS_NAME = "elbatal_phone_auth_registry"
    private const val KEY_PREFIX_PHONE = "phone_email_"
    private const val KEY_PREFIX_PHONE_LOGIN_UID = "phone_login_uid_"

    /**
     * Strips invisible Unicode bidi / zero-width characters often attached when
     * copying phone numbers from Contacts, Call Logs, or WhatsApp on Arabic devices.
     */
    fun cleanInvisibleChars(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return normalizeArabicDigits(raw)
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
            .trim()
    }

    /**
     * Normalizes an Egyptian or general phone number to standard local format (e.g., "01012345678").
     */
    fun normalizeEgyptianPhone(rawPhone: String?): String {
        val cleaned = cleanInvisibleChars(rawPhone)
        val digits = cleaned.replace(Regex("[^0-9]"), "")
        if (digits.isBlank()) return cleaned

        return when {
            digits.startsWith("0020") && digits.length >= 14 -> "0" + digits.removePrefix("0020")
            digits.startsWith("20") && digits.length == 12 -> "0" + digits.removePrefix("20")
            digits.length == 10 && digits.startsWith("1") -> "0$digits"
            else -> digits
        }
    }

    /**
     * Extracts canonical core digits (e.g. "1012345678" for "01012345678" / "+201012345678")
     * for format-independent key matching.
     */
    fun extractCorePhoneDigits(rawPhone: String?): String {
        val digits = cleanInvisibleChars(rawPhone).replace(Regex("[^0-9]"), "")
        if (digits.isBlank()) return ""
        return digits
            .removePrefix("0020")
            .let { if (it.startsWith("20") && it.length >= 11) it.removePrefix("20") else it }
            .removePrefix("0")
    }

    /**
     * Converts ASCII digits in a string to Eastern Arabic numerals (٠١٢٣٤٥٦٧٨٩)
     * so Firestore queries also find legacy documents stored with Arabic numerals.
     */
    private fun toEasternArabicDigits(ascii: String): String {
        val map = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val sb = StringBuilder(ascii.length)
        for (c in ascii) {
            if (c in '0'..'9') sb.append(map[c - '0']) else sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Generates comprehensive phone string variants (up to 30 for Firestore whereIn)
     * covering local 01X, +20, 0020, 20, bare 10-digit, spaced, dashed, and Arabic-digit forms.
     */
    fun generatePhoneQueryVariants(rawInput: String): List<String> {
        val cleaned = cleanInvisibleChars(rawInput)
        val digits = cleaned.replace(Regex("[^0-9]"), "")
        val core = extractCorePhoneDigits(cleaned)
        if (digits.isBlank()) return emptyList()

        val set = linkedSetOf<String>()
        set.add(cleaned)
        set.add(digits)

        if (core.isNotBlank()) {
            val local0 = "0$core"
            set.add(local0)
            set.add("+20$core")
            set.add("20$core")
            set.add("0020$core")
            set.add(core)
            set.add(toEasternArabicDigits(local0))
            set.add(toEasternArabicDigits("+20$core"))

            if (local0.length == 11) {
                set.add("${local0.substring(0, 3)} ${local0.substring(3, 7)} ${local0.substring(7)}")
                set.add("${local0.substring(0, 3)}-${local0.substring(3, 7)}-${local0.substring(7)}")
                set.add("+20 ${core.substring(0, 3)} ${core.substring(3, 6)} ${core.substring(6)}")
            }
        }
        return set.filter { it.isNotBlank() }.take(25)
    }

    /**
     *Generates candidate synthetic email addresses used when accounts are provisioned
     * directly by phone number in admin dashboards or phone-first flows.
     */
    fun generateSyntheticPhoneEmails(rawInput: String): List<String> {
        val core = extractCorePhoneDigits(rawInput)
        val local0 = normalizeEgyptianPhone(rawInput)
        if (local0.isBlank()) return emptyList()

        return listOf(
            "$local0@elbatal.com",
            "$local0@elbatal-express.com",
            "$local0@el-batal.com",
            "$local0@captain.elbatal.com",
            "$local0@batal.com",
            "+20$core@elbatal.com",
            "20$core@elbatal.com",
            "$core@elbatal.com",
            "$local0@captain.com",
            "$local0@app.com",
            "$local0@gmail.com"
        ).distinct()
    }

    fun savePhoneEmailMapping(context: Context?, phone: String?, email: String?) {
        if (context == null || phone.isNullOrBlank() || email.isNullOrBlank()) return
        val core = extractCorePhoneDigits(phone)
        val cleanEmail = cleanInvisibleChars(email)
        if (core.length < 7 || !cleanEmail.contains("@")) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("$KEY_PREFIX_PHONE$core", cleanEmail)
                .apply()
        } catch (_: Exception) {}
    }

    fun getEmailForPhone(context: Context?, phone: String?): String? {
        if (context == null || phone.isNullOrBlank()) return null
        val core = extractCorePhoneDigits(phone)
        if (core.length < 7) return null
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("$KEY_PREFIX_PHONE$core", null)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun markLoggedInViaPhone(context: Context?, uid: String?, viaPhone: Boolean) {
        if (context == null || uid.isNullOrBlank()) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("$KEY_PREFIX_PHONE_LOGIN_UID$uid", viaPhone)
                .apply()
        } catch (_: Exception) {}
    }

    fun wasLoggedInViaPhone(context: Context?, uid: String?): Boolean {
        if (context == null || uid.isNullOrBlank()) return false
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("$KEY_PREFIX_PHONE_LOGIN_UID$uid", false)
        } catch (_: Exception) {
            false
        }
    }
}
