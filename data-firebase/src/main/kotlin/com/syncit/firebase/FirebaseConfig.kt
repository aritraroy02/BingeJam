package com.syncit.firebase

object FirebaseConfig {
    // Realtime Database of Firebase project "syncit-watchparty" (Singapore, asia-southeast1).
    // Set explicitly because non-US databases aren't always in google-services.json.
    // Blank = use the URL from google-services.json.
    const val DATABASE_URL = "https://syncit-watchparty-default-rtdb.asia-southeast1.firebasedatabase.app"

    // The deployed push-relay Worker (push-relay/, Cloudflare account of aritraroy02). Blank = no
    // push notifications. See RUN_ON_MY_MACHINE.md, "Push notifications".
    const val PUSH_RELAY_URL = "https://vyro-push-relay.aritraroy02.workers.dev"
}
