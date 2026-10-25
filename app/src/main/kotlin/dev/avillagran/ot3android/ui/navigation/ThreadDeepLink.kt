package dev.avillagran.ot3android.ui.navigation

import android.net.Uri

/**
 * The thread deep link, `ot3a://thread/{environmentId}/{threadId}?prompt={prompt}`. Parsed here
 * and opened with [Ot3Navigator.openThread] rather than declared as a Navigation deep link: an
 * in-app deep link into a nested graph replaces the whole back stack with the leaf, which would
 * drop Home from under the thread (UI_SPEC §2.1 wants `[Home, Thread]`).
 */
object ThreadDeepLink {
    const val SCHEME = "ot3a"
    const val HOST = "thread"

    fun parse(uri: Uri?): Thread? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments.any { it.isEmpty() }) return null
        return Thread(segments[0], segments[1], uri.getQueryParameter("prompt")?.takeIf { it.isNotEmpty() })
    }

    fun uri(route: Thread): Uri = Uri.Builder().scheme(SCHEME).authority(HOST)
        .appendPath(route.environmentId).appendPath(route.threadId)
        .apply { route.prompt?.let { appendQueryParameter("prompt", it) } }
        .build()
}
