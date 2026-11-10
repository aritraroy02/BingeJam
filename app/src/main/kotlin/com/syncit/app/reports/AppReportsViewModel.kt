package com.syncit.app.reports

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.core.social.AppUser
import com.syncit.core.social.SealedCrash
import com.syncit.core.social.StaffRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One decrypted crash report. */
data class CrashEntry(
    val id: String,
    val kind: String,
    val sig: String,
    val title: String,
    val message: String,
    val where: String,
    val stack: String,
    val logs: String,
    val screen: String,
    val appVersion: String,
    val model: String,
    val android: String,
    val at: Long,
    val uid: String,
    val username: String?,
) {
    /** Plain text for Copy: everything a developer needs in one paste. */
    fun asText(): String = buildString {
        appendLine("$title${if (message.isNotBlank()) ": $message" else ""}")
        appendLine("Where: $where")
        appendLine("Kind: $kind · Screen: $screen")
        appendLine("App $appVersion · $model · Android $android")
        appendLine("User: ${username?.let { "@$it" } ?: uid}")
        appendLine("Time: ${java.time.Instant.ofEpochMilli(at)}")
        appendLine()
        appendLine(stack)
        if (logs.isNotBlank()) {
            appendLine()
            appendLine("Logs:")
            appendLine(logs)
        }
    }
}

/** The same crash from every phone, newest first. */
data class CrashGroup(val sig: String, val entries: List<CrashEntry>) {
    val latest: CrashEntry get() = entries.first()
    val people: Int get() = entries.map { it.uid }.distinct().size
    val versions: List<String> get() = entries.map { it.appVersion }.distinct()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppReportsViewModel(private val container: AppContainer) : ViewModel() {
    private val reports = container.social.appReports

    val role: StateFlow<StaffRole?> = reports.observeRole()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    enum class Lock { LOCKED, UNLOCKED, NO_SCREEN_LOCK }

    private val _lock = MutableStateFlow(Lock.LOCKED)
    val lock: StateFlow<Lock> = _lock.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _crashes = MutableStateFlow<List<CrashGroup>?>(null)
    val crashes: StateFlow<List<CrashGroup>?> = _crashes.asStateFlow()

    /** Reports sent before this phone was set up (encrypted for other keys only). */
    private val _unreadable = MutableStateFlow(0)
    val unreadable: StateFlow<Int> = _unreadable.asStateFlow()

    private val unlocked = MutableStateFlow(false)

    val users: StateFlow<List<AppUser>?> = combine(role, unlocked) { r, u -> r == StaffRole.OWNER && u }
        .flatMapLatest { ok -> if (ok) reports.observeUsers().catch { emit(emptyList()) } else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val developers: StateFlow<List<String>> = combine(role, unlocked) { r, u -> r == StaffRole.OWNER && u }
        .flatMapLatest { ok -> if (ok) reports.observeDevelopers().catch { emit(emptyList()) } else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val cache = HashMap<String, CrashEntry?>()

    init {
        viewModelScope.launch {
            combine(unlocked, reports.observeCrashes().catch { emit(emptyList()) }) { u, list -> if (u) list else null }
                .collectLatest { list -> if (list != null) decrypt(list) }
        }
    }

    /** After the fingerprint/PIN prompt: make (and publish) this phone's key, then show everything. */
    fun onUnlocked(context: Context) {
        viewModelScope.launch {
            try {
                val (keyId, publicKey) = withContext(Dispatchers.Default) { ReportCrypto.ensureKey(context.applicationContext) }
                val published = reports.staffKeys().any { it.keyId == keyId }
                if (!published) reports.publishKey(keyId, publicKey)
                _lock.value = Lock.UNLOCKED
                unlocked.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReportCrypto.NoScreenLockException) {
                _lock.value = Lock.NO_SCREEN_LOCK
            } catch (e: Exception) {
                _notice.value = "Couldn't set up this phone: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun onUnlockFailed(context: Context) {
        val secure = context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
        _lock.value = if (secure) Lock.LOCKED else Lock.NO_SCREEN_LOCK
    }

    private suspend fun decrypt(list: List<SealedCrash>) {
        val keyId = ReportCrypto.myKeyId() ?: return
        try {
            val entries = withContext(Dispatchers.Default) {
                list.map { sealed -> cache.getOrPut(sealed.id) { ReportCrypto.open(sealed, keyId)?.let { parse(sealed, it) } } }
            }
            _unreadable.value = entries.count { it == null }
            _crashes.value = entries.filterNotNull()
                .groupBy { it.sig }
                .map { (sig, items) -> CrashGroup(sig, items.sortedByDescending { it.at }) }
                .sortedByDescending { it.latest.at }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserNotAuthenticatedException) {
            relock()
        } catch (e: KeyPermanentlyInvalidatedException) {
            _notice.value = "This phone's screen lock changed, so its reports key was reset. Unlock again to make a new one."
            relock()
        } catch (e: Exception) {
            if (e.cause is UserNotAuthenticatedException) relock() else _notice.value = "Couldn't open reports: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** The unlock expired (5 minutes): ask again. */
    private fun relock() {
        unlocked.value = false
        _lock.value = Lock.LOCKED
    }

    private fun parse(sealed: SealedCrash, bytes: ByteArray): CrashEntry? = runCatching {
        val j = JSONObject(String(bytes))
        CrashEntry(
            id = sealed.id,
            kind = j.optString("kind"),
            sig = j.optString("sig"),
            title = j.optString("title"),
            message = j.optString("message"),
            where = j.optString("where"),
            stack = j.optString("stack"),
            logs = j.optString("logs"),
            screen = j.optString("screen"),
            appVersion = j.optString("app"),
            model = j.optString("model"),
            android = j.optString("android"),
            at = j.optLong("at", sealed.at),
            uid = sealed.uid,
            username = j.optString("username").ifEmpty { null },
        )
    }.getOrNull()

    fun addDeveloper(email: String) = act("Couldn't add them") {
        reports.addDeveloper(email)
        _notice.value = "Added. They see App reports in Settings once they open it and unlock."
    }

    fun removeDeveloper(email: String) = act("Couldn't remove them") {
        reports.removeDeveloper(email)
        _notice.value = "Removed $email"
    }

    fun send(title: String, body: String, toUids: List<String>?, onSent: () -> Unit) {
        if (_sending.value) return
        viewModelScope.launch {
            _sending.value = true
            try {
                val reached = reports.sendNotification(title.trim(), body.trim(), toUids)
                _notice.value = when {
                    toUids == null -> "Sent to everyone"
                    reached != null -> "Sent to ${toUids.size} ${if (toUids.size == 1) "person" else "people"} ($reached ${if (reached == 1) "phone" else "phones"})"
                    else -> "Sent"
                }
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "Couldn't send"
            } finally {
                _sending.value = false
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun showNotice(text: String) {
        _notice.value = text
    }

    private fun act(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "$failure: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
