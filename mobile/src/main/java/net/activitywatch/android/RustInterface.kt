package net.activitywatch.android

import android.content.Context
import android.util.Log
import net.activitywatch.android.models.Event
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant

private const val TAG = "RemoteRustInterface"

/** Compatibility facade for upstream watchers, backed by the Pifi REST API instead of a local
 * aw-server-rust instance. Keep the upstream class/API shape so its Android watchers can be used. */
class RustInterface(context: Context? = null) {
    private val appContext = context?.applicationContext

    fun startServerTask() {
        // Remote-client build: Pifi owns the ActivityWatch server and datastore.
        Log.i(TAG, "Using remote ActivityWatch server on Pifi; no on-device server is started")
    }

    fun getBuckets(): String = RemoteAwApi.request("GET", "/api/0/buckets/")

    fun createBucket(bucket: String): String = try {
        val body = JSONObject(bucket)
        val id = body.getString("id")
        writeOrQueue("POST", RemoteAwApi.bucketPath(id), body.toString(), "create bucket $id")
    } catch (e: Exception) {
        RemoteAwApi.logFailure("Create bucket", e)
        "error: ${e.message}"
    }

    fun getEvents(bucket_id: String, limit: Int): String {
        val suffix = if (limit > 0) "?limit=$limit" else ""
        return RemoteAwApi.request("GET", "${RemoteAwApi.bucketPath(bucket_id)}/events$suffix")
    }

    fun heartbeat(bucket_id: String, event: String, pulsetime: Double): String =
        try {
            val pulse = if (pulsetime == pulsetime.toLong().toDouble()) pulsetime.toLong().toString() else pulsetime.toString()
            writeOrQueue(
                "POST",
                "${RemoteAwApi.bucketPath(bucket_id)}/heartbeat?pulsetime=$pulse",
                event,
                "write event to $bucket_id",
            )
        } catch (e: Exception) {
            RemoteAwApi.logFailure("Write event to $bucket_id", e)
            "error: ${e.message}"
        }

    fun query(query: String, timeperiods: String): String = RemoteAwApi.query(query, timeperiods)

    /** The upstream widget query is local Rust code. In remote mode issue the equivalent query
     * against Pifi; this preserves total-use data while leaving category rules to Pifi's Web UI. */
    fun androidQuery(timeperiods: String): String {
        val query = """
            events = flood(query_bucket(find_bucket("aw-watcher-android")));
            events = merge_events_by_keys(events, ["app"]);
            duration = sum_durations(events);
            cat_events = [];
            RETURN = {"events": events, "duration": duration, "cat_events": cat_events};
        """.trimIndent()
        return query(query, timeperiods)
    }

    fun getSetting(key: String): String =
        RemoteAwApi.request("GET", "/api/0/settings/${android.net.Uri.encode(key)}")

    fun setSetting(key: String, value: String): String = try {
        val path = "/api/0/settings/${android.net.Uri.encode(key)}"
        val result = writeOrQueue("POST", path, value, "write setting $key")
        if (result == "queued" || result.startsWith("error:")) result else "ok"
    } catch (e: Exception) {
        RemoteAwApi.logFailure("Write setting $key", e)
        "error: ${e.message}"
    }

    fun migrateHostname(hostname: String): String =
        "Remote mode: bucket hostnames are managed by Pifi; no local migration needed"

    fun migrateWatcherAndroidBucketNames(): String =
        "Remote mode: legacy bucket migration is skipped"

    fun sayHello(to: String): String = "Hello, $to!"

    fun createBucketHelper(bucket_id: String, type: String, client: String = "aw-android") {
        val context = appContext ?: throw IllegalStateException("Context is required to create an ActivityWatch bucket")
        try {
            if (getBucketsJSON().has(bucket_id)) return
            val bucket = JSONObject()
                .put("id", bucket_id)
                .put("type", type)
                .put("hostname", getDeviceName(context))
                .put("client", client)
            val response = createBucket(bucket.toString())
            Log.i(TAG, "Created remote bucket $bucket_id: $response")
        } catch (e: Exception) {
            RemoteAwApi.logFailure("Create bucket $bucket_id", e)
        }
    }

    fun heartbeatHelper(
        bucket_id: String,
        timestamp: Instant,
        duration: Double,
        data: JSONObject,
        pulsetime: Double = 60.0,
    ) {
        val event = Event(timestamp, duration, data)
        heartbeat(bucket_id, event.toString(), pulsetime)
    }

    fun insertEvent(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject) {
        val event = Event(timestamp, duration, data)
        heartbeat(bucket_id, event.toString(), 0.0)
    }

    fun getBucketsJSON(): JSONObject = parseBucketsJSON(getBuckets())

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray =
        parseEventsJSON(getEvents(bucket_id, limit))

    fun getDeviceName(context: Context): String = deviceHostname(context)

    private fun writeOrQueue(method: String, path: String, body: String?, action: String): String {
        val context = appContext
        if (context != null && RemoteRequestQueue.hasPending(context)) {
            return queueForRetry(method, path, body, action, IllegalStateException("Earlier requests are still queued"))
        }
        return try {
            RemoteAwApi.request(method, path, body)
        } catch (e: RemoteHttpException) {
            if (e.statusCode == 304 && isBucketCreate(method, path)) {
                Log.i(TAG, "Remote bucket already exists; treating create as complete")
                return "ok"
            }
            if (e.statusCode in 300..399) {
                RemoteAwApi.logFailure(action, e)
                return "error: ${e.message}"
            }
            if (e.statusCode in 400..499 && e.statusCode !in setOf(408, 429)) {
                RemoteAwApi.logFailure(action, e)
                return "error: ${e.message}"
            }
            queueForRetry(method, path, body, action, e)
        } catch (e: Exception) {
            queueForRetry(method, path, body, action, e)
        }
    }

    private fun queueForRetry(method: String, path: String, body: String?, action: String, error: Exception): String {
        val context = appContext
        if (context == null) {
            RemoteAwApi.logFailure(action, error)
            return "error: no app context; ${error.message}"
        }
        return try {
            RemoteRequestQueue(context).use { it.enqueue(method, path, body) }
            RemoteRequestQueue.schedule(context)
            Log.w(
                TAG,
                "$action queued until Pifi is reachable; ${error.javaClass.simpleName}: ${error.message}",
            )
            "queued"
        } catch (queueError: Exception) {
            queueError.addSuppressed(error)
            RemoteAwApi.logFailure("Queue $action", queueError)
            "error: ${queueError.message}"
        }
    }
}

internal fun parseBucketsJSON(response: String): JSONObject {
    val json = JSONObject(response)
    if (json.opt("error") is String) {
        throw JSONException("getBuckets failed: ${json.getString("error")}")
    }
    return json
}

internal fun parseEventsJSON(response: String): JSONArray {
    val trimmed = response.trim()
    if (trimmed.startsWith("{")) {
        val error = try {
            JSONObject(trimmed).opt("error")
        } catch (_: JSONException) {
            null
        }
        if (error is String) throw JSONException("getEvents failed: $error")
    }
    return JSONArray(trimmed)
}
