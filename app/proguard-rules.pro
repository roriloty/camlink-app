# Nothing reflective in the app.

# commons-net references optional classes that are not on Android
-dontwarn org.apache.commons.net.**

# Credential Manager (Sign in with Google account sheet)
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
