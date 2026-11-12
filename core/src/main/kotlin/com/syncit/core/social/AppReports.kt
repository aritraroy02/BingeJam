package com.syncit.core.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * App reports (2026-11-10): crash reports from every phone, the user list and custom notifications,
 * for the owner and the developers the owner adds. Access is enforced by the database rules and the
 * relay from the signed-in account's verified email, never by the app alone.
 */
enum class StaffRole { OWNER, DEVELOPER }

object Staff {
    const val OWNER_EMAIL = "aritraditya.roy@gmail.com"

    /** Database key for an email: lower case, dots as commas (keys can't contain dots). */
    fun emailKey(email: String): String = email.trim().lowercase().replace('.', ',')

    fun emailFromKey(key: String): String = key.replace(',', '.')
}

/** One staff phone's public key; crash reports are encrypted to every one of them. */
data class StaffKey(val emailKey: String, val keyId: String, val publicKey: String)

/**
 * A crash report as stored: [box] is the gzipped JSON report encrypted with AES-256-GCM ([iv]); the
 * AES key is wrapped once per staff key in [keys] (keyId → RSA-OAEP ciphertext). Only [at] (when it
 * happened, for the 7-day clean-up) and [uid] (who sent it, for the rules) are readable.
 */
data class SealedCrash(
    val id: String,
    /** 1 = sent by a phone (RSA-OAEP SHA-256, MGF1 SHA-1); 2 = sent by the PC app (RSA-OAEP SHA-1). */
    val v: Int = 1,
    val iv: String,
    val box: String,
    val keys: Map<String, String>,
    val at: Long,
    val uid: String,
)

/** Someone who joined, as the owner sees them in App reports. */
data class AppUser(
    val uid: String,
    val username: String,
    val displayName: String,
    val photoUrl: String?,
    val email: String?,
    /** "YYYY-MM-DD", private to them and the owner. */
    val birthDate: String?,
    val bio: String?,
    val provider: String?,
    val joinedAt: Long,
    val online: Boolean,
    val lastSeen: Long?,
    val appVersion: String?,
    val device: String?,
    val android: String?,
)

/** The phone this app runs on, recorded for the owner's user list. */
data class DeviceInfo(val appVersion: String, val versionCode: Int, val model: String, val android: String)

interface AppReports {
    /** Our role, or null for everyone else (the App reports entry stays hidden). */
    fun observeRole(): Flow<StaffRole?>

    /** Every staff phone's public key (readable by any signed-in user, writable only by that staff member). */
    suspend fun staffKeys(): List<StaffKey>

    suspend fun publishKey(keyId: String, publicKey: String)

    suspend fun upload(iv: String, box: String, keys: Map<String, String>, at: Long)

    fun observeCrashes(): Flow<List<SealedCrash>>

    /** Owner only. */
    fun observeUsers(): Flow<List<AppUser>>

    /** Owner only: developers' emails. */
    fun observeDevelopers(): Flow<List<String>>

    suspend fun addDeveloper(email: String)

    suspend fun removeDeveloper(email: String)

    /** [toUids] null = everyone (owner and developers); a list = those people (owner only). Returns phones reached, if known. */
    suspend fun sendNotification(title: String, body: String, toUids: List<String>?): Int?

    suspend fun recordDevice(info: DeviceInfo)

    object None : AppReports {
        override fun observeRole(): Flow<StaffRole?> = flowOf(null)
        override suspend fun staffKeys(): List<StaffKey> = emptyList()
        override suspend fun publishKey(keyId: String, publicKey: String) = Unit
        override suspend fun upload(iv: String, box: String, keys: Map<String, String>, at: Long) = Unit
        override fun observeCrashes(): Flow<List<SealedCrash>> = flowOf(emptyList())
        override fun observeUsers(): Flow<List<AppUser>> = flowOf(emptyList())
        override fun observeDevelopers(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun addDeveloper(email: String) = Unit
        override suspend fun removeDeveloper(email: String) = Unit
        override suspend fun sendNotification(title: String, body: String, toUids: List<String>?): Int? = null
        override suspend fun recordDevice(info: DeviceInfo) = Unit
    }
}
