package com.syncit.app.reports

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.syncit.core.social.SealedCrash
import com.syncit.core.social.StaffKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for crash reports (user's request, 2026-11-10: "encrypted entirely").
 *
 * Every staff phone (owner, developers) has an RSA-2048 key pair made in the Android Keystore: the
 * private key never leaves that phone's secure hardware and only works for [UNLOCK_SECONDS] after the
 * person unlocks with their fingerprint, face or screen lock. Its public key is published under their
 * verified email (staffKeys/, see AppReports). A crashing phone gzips the report, encrypts it with a
 * fresh AES-256-GCM key, and wraps that key with RSA-OAEP for each staff public key. The database,
 * the relay and anyone with console access only ever see ciphertext.
 */
object ReportCrypto {
    private const val ALIAS = "bingejam-app-reports"
    private const val KEYSTORE = "AndroidKeyStore"
    const val UNLOCK_SECONDS = 300

    /** RSA-OAEP with SHA-256; MGF1 stays SHA-1, the only MGF1 digest every Android Keystore accepts. */
    private const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    class NoScreenLockException : Exception("Set a screen lock (PIN, pattern or fingerprint) on this phone first")

    data class Sealed(val iv: String, val box: String, val keys: Map<String, String>)

    private val random = SecureRandom()

    // ---- Sending side (every phone) ----

    fun seal(plain: ByteArray, recipients: List<StaffKey>): Sealed {
        require(recipients.isNotEmpty()) { "No staff keys" }
        val aes = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        val iv = ByteArray(12).also(random::nextBytes)
        val box = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
            doFinal(gzip(plain))
        }
        val keyFactory = KeyFactory.getInstance("RSA")
        val wrapped = recipients.associate { r ->
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(Base64.decode(r.publicKey, Base64.NO_WRAP)))
            val cipher = Cipher.getInstance(RSA).apply { init(Cipher.ENCRYPT_MODE, publicKey, OAEP) }
            r.keyId to b64(cipher.doFinal(aes.encoded))
        }
        return Sealed(b64(iv), b64(box), wrapped)
    }

    // ---- Staff side ----

    /** This phone's public key and its id, creating the key pair the first time. */
    fun ensureKey(context: Context): Pair<String, String> {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard?.isDeviceSecure != true) throw NoScreenLockException()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(UNLOCK_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(UNLOCK_SECONDS)
                    }
                }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
        val publicKey = b64(store.getCertificate(ALIAS).publicKey.encoded)
        return keyId(publicKey) to publicKey
    }

    fun myKeyId(): String? {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) return null
        return keyId(b64(store.getCertificate(ALIAS).publicKey.encoded))
    }

    /**
     * The report's plain bytes, or null if it wasn't encrypted for this phone (sent before this phone
     * was set up). Throws android.security.keystore.UserNotAuthenticatedException when the unlock expired.
     */
    fun open(sealed: SealedCrash, keyId: String): ByteArray? {
        val wrapped = sealed.keys[keyId] ?: return null
        val privateKey = keyStore().getKey(ALIAS, null) as? PrivateKey ?: return null
        val aesKey = Cipher.getInstance(RSA).run {
            init(Cipher.DECRYPT_MODE, privateKey, OAEP)
            doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, Base64.decode(sealed.iv, Base64.NO_WRAP)))
            doFinal(Base64.decode(sealed.box, Base64.NO_WRAP))
        }
        return gunzip(plain)
    }

    /** Short, stable id: the first 16 characters of the public key's SHA-256, base64url. */
    fun keyId(publicKey: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(16)

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
