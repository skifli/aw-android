package net.activitywatch.android

import android.content.Context
import android.util.Log
import net.activitywatch.android.models.Event
import org.json.JSONArray
import org.json.JSONObject
import org.threeten.bp.Instant

private const val TAG = "RustInterface"

/**
 * No-op facade kept only so the app can compile while the embedded Rust server is removed.
 *
 * The app should connect to an external ActivityWatch server instead of bundling one.
 */
class RustInterface(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext

    fun sayHello(to: String): String {
        return "Hello, $to"
    }

    fun createBucketHelper(bucket_id: String, type: String, client: String = "aw-android") {
        Log.i(TAG, "Embedded server removed; createBucketHelper($bucket_id) is a no-op")
    }

    fun heartbeatHelper(
        bucket_id: String,
        timestamp: Instant,
        duration: Double,
        data: JSONObject,
        pulsetime: Double = 60.0
    ) {
        Log.i(TAG, "Embedded server removed; heartbeatHelper($bucket_id) is a no-op")
    }

    fun insertEvent(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject) {
        Log.i(TAG, "Embedded server removed; insertEvent($bucket_id) is a no-op")
    }

    fun getBucketsJSON(): JSONObject {
        return JSONObject()
    }

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray {
        return JSONArray()
    }

    fun androidQuery(timeperiods: String): String {
        Log.i(TAG, "Embedded server removed; androidQuery() is a no-op")
        return "[]"
    }

    fun getDeviceName(context: Context): String {
        return android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: android.os.Build.MODEL ?: "Unknown"
    }

    fun test() {
        Log.w(TAG, "Embedded server removed; test() is a no-op")
    }
}
