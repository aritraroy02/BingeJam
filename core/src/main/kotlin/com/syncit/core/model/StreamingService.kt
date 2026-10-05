package com.syncit.core.model

import com.syncit.core.config.StreamingConfig

/**
 * A streaming app we can sync. [linkHosts] are only used to warn about a pasted link that
 * looks wrong; any http(s) link is still accepted.
 */
enum class StreamingService(
    val displayName: String,
    val packageName: String,
    val linkHosts: List<String>,
) {
    NETFLIX("Netflix", StreamingConfig.NETFLIX_PACKAGE, listOf("netflix.com")),

    // linkHosts are a soft hint; confirm against a real JioHotstar share link.
    JIOHOTSTAR("JioHotstar", StreamingConfig.JIOHOTSTAR_PACKAGE, listOf("hotstar.com", "jiohotstar.com"));

    /** False until the package name has been filled in (see StreamingConfig). */
    val isConfigured: Boolean get() = packageName.isNotBlank()

    companion object {
        fun fromId(id: String?): StreamingService? = entries.firstOrNull { it.name == id }

        fun forPackage(packageName: String): StreamingService? =
            entries.firstOrNull { it.isConfigured && it.packageName == packageName }
    }
}
