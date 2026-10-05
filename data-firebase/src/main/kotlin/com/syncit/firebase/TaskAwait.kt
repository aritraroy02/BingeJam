package com.syncit.firebase

import com.google.android.gms.tasks.Task
import com.google.firebase.database.DatabaseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until a Play Services Task finishes. A few lines here instead of pulling in
 * kotlinx-coroutines-play-services.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        when {
            task.isCanceled -> cont.cancel(CancellationException("Task was cancelled"))
            task.isSuccessful -> {
                @Suppress("UNCHECKED_CAST")
                cont.resume(task.result as T)
            }
            else -> cont.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
        }
    }
}

/**
 * Firebase reports security-rule rejections as DatabaseError.toException(), a DatabaseException
 * whose message is "Firebase Database error: Permission denied".
 */
internal fun Throwable.isPermissionDenied(): Boolean =
    this is DatabaseException && message?.contains("Permission denied", ignoreCase = true) == true
