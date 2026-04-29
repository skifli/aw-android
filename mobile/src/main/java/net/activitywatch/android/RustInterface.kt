package net.activitywatch.android

import android.content.Context
import android.util.Log
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.threeten.bp.Instant
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TAG = "RustInterface"
private const val DEFAULT_REMOTE_HOST = "100.120.18.23"
private const val DEFAULT_BUCKET_ID = "aw-watcher-android-test"

/** Small HTTP bridge to an external ActivityWatch server. */
class RustInterface(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext
    private val prefs: AWPreferences? = appContext?.let { AWPreferences(it) }

    fun sayHello(to: String): String {
        return "Hello, $to"
    }

    fun createBucketHelper(bucket_id: String, type: String, client: String = "aw-android") {
        if (appContext == null) return

        val payload = JSONObject()
            .put("client", client)
            .put("hostname", getDeviceName(appContext))
            .put("type", type)

        runFireAndForget {
            try {
                requestText("POST", "buckets/$bucket_id", payload.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bucket $bucket_id", e)
            }
        }
    }

    fun heartbeatHelper(
        bucket_id: String,
        timestamp: Instant,
        duration: Double,
        data: JSONObject,
        pulsetime: Double = 60.0
    ) {
        val payload = buildEventJson(timestamp, duration, data)
        runFireAndForget {
            try {
                requestText(
                    "POST",
                    "buckets/$bucket_id/heartbeat?pulsetime=$pulsetime",
                    payload.toString()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send heartbeat to $bucket_id", e)
            }
        }
    }

    fun insertEvent(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject) {
        val payload = JSONArray().put(buildEventJson(timestamp, duration, data))
        runFireAndForget {
            try {
                requestText("POST", "buckets/$bucket_id/events", payload.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert event into $bucket_id", e)
            }
        }
    }

    fun getBucketsJSON(): JSONObject {
        return try {
            JSONObject(requestText("GET", "buckets/"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch buckets", e)
            JSONObject()
        }
    }

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray {
        val endpoint = buildString {
            append("buckets/")
            append(bucket_id)
            append("/events")
            if (limit > 0) {
                append("?limit=")
                append(limit)
            }
        }

        return try {
            JSONArray(requestText("GET", endpoint))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch events for $bucket_id", e)
            JSONArray()
        }
    }

    fun androidQuery(timeperiods: String): String {
        return try {
            val parsedTimeperiods = parseTimeperiods(timeperiods)
            val query = buildAndroidQuery()
            executeQuery(query, parsedTimeperiods)
        } catch (e: Exception) {
            Log.e(TAG, "androidQuery failed", e)
            "[]"
        }
    }

    fun getDeviceName(context: Context): String {
        return android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: android.os.Build.MODEL ?: "Unknown"
    }

    fun test() {
        Log.i(TAG, "Remote ActivityWatch client ready")
    }

    private fun buildBaseUrl(): String {
        val host = prefs?.getRemoteServerHost().orEmpty().ifEmpty { DEFAULT_REMOTE_HOST }
        val port = prefs?.getRemoteServerPort() ?: 5600
        return "http://$host:$port"
    }

    private fun runFireAndForget(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread(block).start()
        } else {
            block()
        }
    }

    private fun requestText(method: String, endpoint: String, body: String? = null): String {
        val connection = (URL("${buildBaseUrl()}/api/0/$endpoint").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            doInput = true
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NO_CONTENT || responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return ""
            }

            val stream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (responseCode >= 400) {
                throw IOException("HTTP $responseCode from $endpoint: $responseText")
            }

            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun buildEventJson(timestamp: Instant, duration: Double, data: JSONObject): JSONObject {
        return JSONObject()
            .put("timestamp", timestamp.toString())
            .put("duration", duration)
            .put("data", data)
    }

    private fun parseTimeperiods(timeperiods: String): List<String> {
        val parsed = JSONArray(timeperiods)
        val periods = mutableListOf<String>()
        for (i in 0 until parsed.length()) {
            periods.add(parsed.getString(i))
        }
        return periods
    }

    private fun buildAndroidQuery(): String {
        val classes = loadClassesForQuery()
        val classesClause = if (classes.length() > 0) {
            "events = categorize(events, ${classes.toString()});"
        } else {
            ""
        }

        return """
            events = flood(query_bucket(find_bucket("$DEFAULT_BUCKET_ID")));
            events = merge_events_by_keys(events, ["app"]);
            $classesClause
            duration = sum_durations(events);
            cat_events = sort_by_duration(merge_events_by_keys(events, ["${'$'}category"]));
            RETURN = {"events": events, "duration": duration, "cat_events": cat_events};
        """.trimIndent()
    }

    private fun loadClassesForQuery(): JSONArray {
        return try {
            val serverClasses = JSONArray(requestText("GET", "settings/classes"))
            val queryClasses = JSONArray()

            for (i in 0 until serverClasses.length()) {
                val clazz = serverClasses.getJSONObject(i)
                val name = clazz.optJSONArray("name") ?: continue
                val rule = clazz.optJSONObject("rule") ?: continue
                queryClasses.put(JSONArray().put(name).put(rule))
            }

            queryClasses
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to unclassified android query", e)
            JSONArray()
        }
    }

    private fun executeQuery(query: String, timeperiods: List<String>): String {
        val payload = JSONObject()
            .put("query", query.split('\n'))
            .put("timeperiods", JSONArray(timeperiods))

        return requestText("POST", "query/", payload.toString())
    }
}
