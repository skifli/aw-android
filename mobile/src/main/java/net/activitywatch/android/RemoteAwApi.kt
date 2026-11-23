package net.activitywatch.android

import android.net.Uri
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** HTTP client for the Pifi ActivityWatch instance. This app is a remote client: it does not start
 * an ActivityWatch server on the phone. Traffic travels over the private Tailscale network. */
internal object RemoteAwApi {
    private const val TAG = "RemoteAwApi"
    private const val BASE_URL = BuildConfig.REMOTE_SERVER_URL
    private const val TIMEOUT_MS = 8_000

    fun request(method: String, path: String, body: String? = null): String {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw RemoteHttpException(status, "Pifi ActivityWatch returned HTTP $status: ${response.take(300)}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun bucketPath(bucketId: String): String = "/api/0/buckets/${Uri.encode(bucketId)}"

    fun query(query: String, timeperiods: String): String {
        val payload = JSONObject()
            .put("query", JSONArray().put(query))
            .put("timeperiods", JSONArray(timeperiods))
        return request("POST", "/api/0/query", payload.toString())
    }

    fun logFailure(action: String, error: Throwable) {
        Log.e(TAG, "$action failed against $BASE_URL", error)
    }
}

internal class RemoteHttpException(val statusCode: Int, message: String) : IOException(message)
