# Firebase, Compose and AndroidX ship their own consumer rules.
# Keep line numbers so release crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Credential Manager loads its Play Services provider by reflection (androidx.credentials docs).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
