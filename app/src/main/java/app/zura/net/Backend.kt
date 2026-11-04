package app.zura.net

import app.zura.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the Supabase project. Auth sessions are NOT persisted by the library —
 * [app.zura.account.AccountManager] stores them itself, encrypted, per account.
 */
class Backend {
    val url = BuildConfig.SUPABASE_URL
    private val key = BuildConfig.SUPABASE_KEY

    val client: SupabaseClient = createSupabaseClient(url, key) {
        install(Auth) {
            // Never let the library persist tokens (it would use plain SharedPreferences).
            // AccountManager stores sessions itself, encrypted with hardware-backed keys.
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            autoLoadFromStorage = false
            autoSaveToStorage = false
            alwaysAutoRefresh = true
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
        install(Realtime)
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val refreshLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Make sure the access token is valid before a request. After a cold start (woken by a push,
     * hours later) the restored token is often expired and the background refresher may not have
     * run yet — the request would 401 and the message would wait for the next sync.
     */
    suspend fun ensureFresh() {
        val s = client.auth.currentSessionOrNull() ?: return
        if (s.expiresAt.toEpochMilliseconds() - System.currentTimeMillis() > 60_000) return
        refreshLock.withLock {
            val again = client.auth.currentSessionOrNull() ?: return
            if (again.expiresAt.toEpochMilliseconds() - System.currentTimeMillis() > 60_000) return
            // Bounded: a hung refresh must never stall every request behind this lock.
            kotlinx.coroutines.withTimeoutOrNull(12_000) { runCatching { client.auth.refreshCurrentSession() } }
        }
    }

    private fun token(): String = client.auth.currentAccessTokenOrNull() ?: error("not signed in")

    private fun authed(path: String) = Request.Builder().url("$url$path")
        .header("apikey", key)
        .header("Authorization", "Bearer ${token()}")

    // ---------------------------------------------------------------- PostgREST insert (no reflection)

    /**
     * INSERT one row via PostgREST over plain HTTPS. Deliberately NOT supabase-kt's reified
     * `insert(value)`: that wraps the value in a List and resolves its serializer with typeOf(),
     * which R8 breaks in release builds ("Unresolved class: interface java.util.List") — every
     * message silently failed to send on real phones while debug tests passed.
     */
    suspend fun insertRow(table: String, row: JsonObject) = withContext(Dispatchers.IO) {
        ensureFresh()
        val req = authed("/rest/v1/$table")
            .header("Prefer", "return=minimal")
            .post(row.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "insert $table ${r.code}: ${r.body?.string()?.take(200)}" }
        }
    }

    // ---------------------------------------------------------------- storage (streaming, no buffering)

    suspend fun upload(bucket: String, path: String, file: File, mime: String = "application/octet-stream") = withContext(Dispatchers.IO) {
        ensureFresh()
        val req = authed("/storage/v1/object/$bucket/$path").header("x-upsert", "true")
            .post(file.asRequestBody(mime.toMediaType())).build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "upload ${it.code}: ${it.body?.string()}" } }
    }

    suspend fun upload(bucket: String, path: String, bytes: ByteArray, mime: String) = withContext(Dispatchers.IO) {
        ensureFresh()
        val req = authed("/storage/v1/object/$bucket/$path").header("x-upsert", "true")
            .post(bytes.toRequestBody(mime.toMediaType())).build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "upload ${it.code}: ${it.body?.string()}" } }
    }

    suspend fun <T> download(bucket: String, path: String, sink: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        ensureFresh()
        http.newCall(authed("/storage/v1/object/authenticated/$bucket/$path").get().build()).execute().use { r ->
            check(r.isSuccessful) { "download ${r.code}" }
            sink(r.body!!.byteStream())
        }
    }

    suspend fun delete(bucket: String, path: String) = withContext(Dispatchers.IO) {
        ensureFresh()
        runCatching { http.newCall(authed("/storage/v1/object/$bucket/$path").delete().build()).execute().close() }
    }

    // ---------------------------------------------------------------- realtime broadcast (send-only, REST)

    /**
     * Sends an ephemeral event to another user's private topic "u:<uid>". The database policy on
     * realtime.messages only lets accepted contacts write there; only the owner can listen.
     * REST keeps us from having to *join* the peer's channel (which RLS forbids, by design).
     */
    suspend fun broadcast(toUser: String, event: String, payload: JsonObject) = withContext(Dispatchers.IO) {
        ensureFresh()
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("topic", "u:$toUser")
                    put("event", event)
                    put("payload", payload)
                    put("private", true)
                })
            })
        }
        val req = authed("/realtime/v1/api/broadcast")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    // ---------------------------------------------------------------- push wake-ups (FCM via edge function)

    /** Fire-and-forget wake-up for a contact's devices. Never carries message content. */
    suspend fun push(to: String, kind: String, data: Map<String, String> = emptyMap(), normalPriority: Boolean = false) {
        runCatching {
            function("push", buildJsonObject {
                put("to", to); put("kind", kind)
                if (normalPriority) put("priority", "normal")
                put("data", buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
            })
        }
    }

    // ---------------------------------------------------------------- edge functions

    suspend fun function(name: String, body: JsonObject = JsonObject(emptyMap())): JsonElement = withContext(Dispatchers.IO) {
        ensureFresh()
        val req = authed("/functions/v1/$name").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "function $name ${r.code}" }
            json.parseToJsonElement(r.body!!.string())
        }
    }
}
