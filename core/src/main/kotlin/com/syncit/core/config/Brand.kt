package com.syncit.core.config

/**
 * Product name in one place. "Vyro" is a working name (already trademarked elsewhere), so the
 * user will rename it: change these constants, `app_name` in app/src/main/res/values/strings.xml
 * and the web/ pages. The package name stays com.syncit.app (changing it breaks installs,
 * Firebase and App Links).
 */
object Brand {
    const val NAME = "Vyro"
    const val TAGLINE = "Your world. In sync."
}
