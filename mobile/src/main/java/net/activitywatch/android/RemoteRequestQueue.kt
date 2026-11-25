package net.activitywatch.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val TAG = "RemoteRequestQueue"
private const val QUEUE_WORK_NAME = "pifi-remote-request-queue"
private const val QUEUE_PERIODIC_WORK_NAME = "pifi-remote-request-periodic-drain"

internal data class QueuedRemoteRequest(
    val id: Long,
    val method: String,
    val path: String,
    val body: String?,
)

/** Durable ordered queue for writes that fail while Pifi or Tailscale is unreachable. */
internal class RemoteRequestQueue(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "pifi_remote_requests.db",
    null,
    1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pending_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                body TEXT,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enqueue(method: String, path: String, body: String?) {
        writableDatabase.execSQL(
            "INSERT INTO pending_requests(method, path, body, created_at) VALUES(?, ?, ?, ?)",
            arrayOf(method, path, body, System.currentTimeMillis()),
        )
    }

    fun next(): QueuedRemoteRequest? = readableDatabase.rawQuery(
        "SELECT id, method, path, body FROM pending_requests ORDER BY id LIMIT 1",
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        QueuedRemoteRequest(
            cursor.getLong(0),
            cursor.getString(1),
            cursor.getString(2),
            if (cursor.isNull(3)) null else cursor.getString(3),
        )
    }

    fun remove(id: Long) {
        writableDatabase.delete("pending_requests", "id = ?", arrayOf(id.toString()))
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_requests", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0
    }

    fun removeExactDuplicates(): Long {
        val before = count()
        writableDatabase.execSQL(
            """DELETE FROM pending_requests
               WHERE id NOT IN (
                   SELECT MIN(id) FROM pending_requests GROUP BY method, path, body
               )""".trimIndent(),
        )
        return before - count()
    }

    companion object {
        fun hasPending(context: Context): Boolean =
            RemoteRequestQueue(context).use { it.count() > 0 }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<RemoteQueueWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                QUEUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                work,
            )
        }

        /** Reset an old, heavily backed-off drain when the foreground service starts. */
        fun restartDrain(context: Context) {
            val duplicates = RemoteRequestQueue(context).use { it.removeExactDuplicates() }
            if (duplicates > 0) Log.i(TAG, "Removed $duplicates identical duplicate request(s) from the pending queue")
            if (!hasPending(context)) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<RemoteQueueWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            Log.i(TAG, "Restarting delayed queue drain because pending uploads remain")
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                QUEUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                work,
            )
        }

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = PeriodicWorkRequestBuilder<RemoteQueueWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                QUEUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }
    }
}

internal class RemoteQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        RemoteRequestQueue(applicationContext).use { queue ->
            Log.i(TAG, "Starting queue drain with ${queue.count()} pending request(s)")
            var processed = 0
            while (true) {
                val pending = queue.next() ?: return Result.success()
                try {
                    RemoteAwApi.request(pending.method, pending.path, pending.body)
                    queue.remove(pending.id)
                    processed++
                    if (processed % 500 == 0) {
                        Log.i(TAG, "Drained $processed request(s); ${queue.count()} remain")
                    }
                } catch (e: RemoteHttpException) {
                    if (e.statusCode == 304 && isBucketCreate(pending.method, pending.path)) {
                        Log.i(TAG, "Bucket already exists; treating queued create as complete")
                        queue.remove(pending.id)
                    } else if (e.statusCode in 300..399 || (e.statusCode in 400..499 && e.statusCode !in setOf(408, 429))) {
                        Log.e(TAG, "Dropping permanently rejected queued request ${pending.id}: ${e.message}")
                        queue.remove(pending.id)
                    } else {
                        Log.w(TAG, "Pifi still unavailable; ${queue.count()} request(s) remain queued", e)
                        return Result.retry()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Pifi still unavailable; ${queue.count()} request(s) remain queued", e)
                    return Result.retry()
                }
            }
        }
    }
}

internal fun isBucketCreate(method: String, path: String): Boolean =
    method == "POST" && path.startsWith("/api/0/buckets/") &&
        path.removePrefix("/api/0/buckets/").isNotBlank() &&
        !path.removePrefix("/api/0/buckets/").contains('/')
