package app.zura.account

import android.app.Application
import android.content.Context
import app.zura.R
import app.zura.net.Backend
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val session: AccountSession) : AuthState
}

sealed interface AuthResult {
    data object Ok : AuthResult
    /** Account created; Supabase requires the email address to be confirmed before first sign-in. */
    data object ConfirmEmail : AuthResult
    /** Saved session expired or was revoked — ask for the password only. */
    data object NeedPassword : AuthResult
    data class Error(val message: String) : AuthResult
}

/**
 * Sign-up / sign-in / sign-out and the "accounts used on this device" picker.
 *
 *  • Remember me ON  → the refresh session is stored encrypted; tapping the account card signs in
 *                      instantly (no password). Signing out keeps the card and the saved session.
 *  • Remember me OFF → sign-out revokes the session server-side; the card stays, and tapping it
 *                      asks for the password only (email pre-filled).
 *  • "Remove from this device" crypto-shreds the account's keys, chats and media.
 */
class AccountManager(
    private val app: Application,
    private val backend: Backend,
    val store: AccountStore,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = app.getSharedPreferences("zura_auth", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    val session: StateFlow<AccountSession?> = _state.map { (it as? AuthState.SignedIn)?.session }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Persist every token refresh for the active, remembered account.
        scope.launch {
            backend.client.auth.sessionStatus.collect { st ->
                val active = (state.value as? AuthState.SignedIn)?.session ?: return@collect
                if (st is SessionStatus.Authenticated && store.get(active.uid)?.remember == true && st.session.user?.id == active.uid) {
                    runCatching { store.session(active.uid).writeText(json.encodeToString(UserSession.serializer(), st.session)) }
                }
            }
        }
        scope.launch {
            val last = prefs.getString(K_ACTIVE, null)
            val acct = last?.let(store::get)
            _state.value = AuthState.SignedOut
            if (acct != null && acct.remember) quickLogin(acct.uid)
        }
    }

    // ---------------------------------------------------------------- sign-up

    suspend fun usernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            backend.client.postgrest.rpc("username_available", buildJsonObject { put("p_username", username) }).data.trim() == "true"
        }.getOrDefault(true)
    }

    suspend fun register(email: String, password: String, username: String, displayName: String, avatarJpeg: ByteArray?, remember: Boolean): AuthResult =
        lock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    // The confirmation link opens the app directly (zura://auth/callback) and signs in.
                    val user = backend.client.auth.signUpWith(Email, redirectUrl = AUTH_CALLBACK) {
                        this.email = email.trim(); this.password = password
                        data = buildJsonObject { put("username", username.trim()); put("display_name", displayName.trim()) }
                    }
                    val s = backend.client.auth.currentSessionOrNull()
                    val uid = s?.user?.id ?: user?.id ?: error("sign-up failed")
                    store.upsert(SavedAccount(uid, email.trim(), username.trim(), displayName.trim(), System.currentTimeMillis(), remember))
                    avatarJpeg?.let { store.pendingAvatar(uid).write(it); store.avatar(uid).write(it) }
                    if (s == null) return@runCatching AuthResult.ConfirmEmail
                    activate(uid, email.trim(), remember, s)
                    AuthResult.Ok
                }.getOrElse { AuthResult.Error(friendly(it)) }
            }
        }

    // ---------------------------------------------------------------- sign-in

    suspend fun login(email: String, password: String, remember: Boolean): AuthResult = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                backend.client.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
                val s = backend.client.auth.currentSessionOrNull() ?: error("no session")
                activate(s.user!!.id, email.trim(), remember, s)
                AuthResult.Ok
            }.getOrElse { AuthResult.Error(friendly(it)) }
        }
    }

    /** One tap on a saved account. Uses the stored session; falls back to asking for the password. */
    suspend fun quickLogin(uid: String): AuthResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val acct = store.get(uid) ?: return@withContext AuthResult.NeedPassword
            val saved = store.session(uid).readText()?.let { runCatching { json.decodeFromString(UserSession.serializer(), it) }.getOrNull() }
                ?: return@withContext AuthResult.NeedPassword
            runCatching {
                backend.client.auth.importSession(saved)
                backend.client.auth.refreshCurrentSession()
                val s = backend.client.auth.currentSessionOrNull() ?: error("expired")
                check(s.user?.id == uid || s.user == null)
                activate(uid, acct.email, acct.remember, s)
                AuthResult.Ok
            }.getOrElse {
                store.session(uid).shred()
                AuthResult.NeedPassword
            }
        }
    }

    /**
     * The email-confirmation link came back to the app: zura://auth/callback#access_token=…&refresh_token=…
     * Import that session and sign straight in — no password needed.
     */
    suspend fun handleAuthLink(uri: android.net.Uri): AuthResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val raw = uri.fragment ?: uri.query ?: return@withContext AuthResult.Error(app.getString(R.string.err_generic))
            val p = raw.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to android.net.Uri.decode(it[1]) } }.toMap()
            p["error_description"]?.let { return@withContext AuthResult.Error(it.replace('+', ' ')) }
            val access = p["access_token"] ?: return@withContext AuthResult.Error(app.getString(R.string.err_generic))
            val refresh = p["refresh_token"] ?: return@withContext AuthResult.Error(app.getString(R.string.err_generic))
            runCatching {
                backend.client.auth.importAuthToken(access, refresh, retrieveUser = true, autoRefresh = true)
                val s = backend.client.auth.currentSessionOrNull() ?: error("no session")
                val uid = s.user?.id ?: error("no user")
                val email = s.user?.email ?: store.get(uid)?.email.orEmpty()
                activate(uid, email, store.get(uid)?.remember ?: true, s)
                AuthResult.Ok
            }.getOrElse { AuthResult.Error(friendly(it)) }
        }
    }

    // ---------------------------------------------------------------- sign-out

    suspend fun logout(remember: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            val active = (state.value as? AuthState.SignedIn)?.session ?: return@withContext
            active.unregisterPush() // a signed-out account must never ring this phone
            active.close()
            store.get(active.uid)?.let { store.upsert(it.copy(remember = remember, lastUsed = System.currentTimeMillis())) }
            if (!remember) {
                runCatching { backend.client.auth.signOut(SignOutScope.LOCAL) } // revokes the refresh token
                store.session(active.uid).shred()
            }
            runCatching { backend.client.auth.clearSession() }
            prefs.edit().remove(K_ACTIVE).apply()
            _state.value = AuthState.SignedOut
        }
    }

    /** Remove an account and every byte of its data from this device. */
    suspend fun forget(uid: String) {
        if ((state.value as? AuthState.SignedIn)?.session?.uid == uid) logout(remember = false)
        withContext(Dispatchers.IO) { store.forget(uid) }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun activate(uid: String, email: String, remember: Boolean, s: UserSession) {
        (state.value as? AuthState.SignedIn)?.session?.close()
        val profile = runCatching {
            backend.json.decodeFromString(Profile.serializer(),
                backend.client.from("profiles").select { filter { eq("id", uid) }; single() }.data)
        }.getOrNull()
        store.upsert(
            SavedAccount(uid, email, profile?.username ?: store.get(uid)?.username.orEmpty(),
                profile?.displayName ?: store.get(uid)?.displayName.orEmpty(), System.currentTimeMillis(), remember),
        )
        if (remember) store.session(uid).writeText(json.encodeToString(UserSession.serializer(), s)) else store.session(uid).shred()
        prefs.edit().putString(K_ACTIVE, uid).apply()

        val session = AccountSession(app, uid, email, backend, store)
        session.bootstrap(app.getString(R.string.notes_title))
        _state.value = AuthState.SignedIn(session)

        session.scope.launch {
            // Upload an avatar chosen at sign-up (if email confirmation delayed the first session).
            store.pendingAvatar(uid).read()?.let { bytes ->
                runCatching { session.contacts.setAvatar(bytes) }.onSuccess { store.pendingAvatar(uid).shred() }
            }
            // Keep a local encrypted copy of my avatar for the account picker.
            session.contacts.myProfile.collect { p ->
                p?.avatarPath?.let { path -> runCatching { backend.download("avatars", path) { it.readBytes() } }.getOrNull()?.let(store.avatar(uid)::write) }
                p?.let { store.get(uid)?.let { a -> store.upsert(a.copy(username = it.username, displayName = it.displayName)) } }
            }
        }
    }

    /** Test hook: show the signed-in UI for a locally built session (no network). */
    @androidx.annotation.VisibleForTesting
    internal fun signInForTest(s: AccountSession) { _state.value = AuthState.SignedIn(s) }

    private fun friendly(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            "Invalid login credentials" in m -> app.getString(R.string.err_credentials)
            "already registered" in m || "already been registered" in m -> app.getString(R.string.err_email_taken)
            "Password should be" in m || "weak" in m.lowercase() -> app.getString(R.string.err_weak_password)
            "Email not confirmed" in m -> app.getString(R.string.err_confirm_email)
            "Unable to resolve host" in m || "timeout" in m.lowercase() || "Failed to connect" in m -> app.getString(R.string.err_network)
            else -> m.lineSequence().firstOrNull()?.take(160) ?: app.getString(R.string.err_generic)
        }
    }

    companion object {
        private const val K_ACTIVE = "active_uid"
        const val AUTH_CALLBACK = "zura://auth/callback"
    }
}
