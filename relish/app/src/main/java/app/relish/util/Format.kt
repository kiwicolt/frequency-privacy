package app.relish.util

import java.util.Locale

fun timeAgo(createdUtcSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val s = (nowMillis / 1000 - createdUtcSeconds).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        s < 2_592_000 -> "${s / 86_400}d ago"
        s < 31_536_000 -> "${s / 2_592_000}mo ago"
        else -> "${s / 31_536_000}y ago"
    }
}

fun compactCount(n: Int): String = compactCount(n.toLong())

fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fm", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
    else -> n.toString()
}
