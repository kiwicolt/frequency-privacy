package app.relish.util

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Runs [block] and, when [enabled], keeps going for at least [minMillis]. Used for pull-to-refresh:
 * Material's indicator only slides back once it has *seen* isRefreshing turn true and then false,
 * so a result that arrives within the same frame (cache, demo data) would leave it stuck.
 */
suspend fun <T> withMinDuration(enabled: Boolean, minMillis: Long = 450, block: suspend () -> T): T {
    val start = SystemClock.elapsedRealtime()
    val result = block()
    if (enabled) {
        val remaining = minMillis - (SystemClock.elapsedRealtime() - start)
        if (remaining > 0) delay(remaining)
    }
    return result
}
