# google-services.json goes here

Download it from the Firebase console (Project settings → Your apps → Android app
`com.syncit.app`) and save it as `app/google-services.json`, next to this file.

It's git-ignored. The build fails at `processDebugGoogleServices` until it's present.
Full steps: RUN_ON_MY_MACHINE.md → "Firebase setup".
