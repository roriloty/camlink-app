package app.camlink.usb.upload

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google sign-in for Drive through Google Play services. No client secret lives in the app: Google
 * recognises it by package name + signing certificate (registered once in Google Cloud Console).
 */
object DriveAuth {
    /**
     * Web OAuth client ID of the same Google Cloud project, used only to show Google's account sheet
     * ("Sign in with Google") so any account on the phone can be picked. Empty = use Android's account picker.
     */
    const val WEB_CLIENT_ID = "600109688882-5l9qpcflqhtg0cvsu6smp7pipbj6eqpk.apps.googleusercontent.com"

    /** Shows Google's account sheet (every account on the phone, plus "Add account") and returns the chosen e-mail. */
    suspend fun chooseAccount(activity: android.app.Activity): String {
        val cm = androidx.credentials.CredentialManager.create(activity)
        val option = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()
        val req = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = cm.getCredential(activity, req).credential
        if (cred is androidx.credentials.CustomCredential &&
            cred.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            return com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(cred.data).id
        throw IOException("Google didn't return an account")
    }

    /** Limited Drive access: only files and folders this app created (non-sensitive, no security audit needed). */
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    private fun request(account: String?) = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .apply { if (!account.isNullOrBlank()) setAccount(Account(account, "com.google")) }
        .build()

    /** First step of signing in from the UI. If the result has a resolution, launch its pendingIntent. */
    suspend fun begin(context: Context, account: String? = null): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request(account)).await()

    /** Second step, with the data returned by the consent screen. */
    fun finish(context: Context, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /** Access token for background work (blocking; never shows UI). */
    fun tokenBlocking(context: Context, account: String): String {
        val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(request(account)))
        if (r.hasResolution() || r.accessToken == null)
            throw IOException("Google Drive needs you to sign in again (open Settings)")
        return r.accessToken!!
    }

    /** Drops a token Google rejected, so the next request gets a fresh one. */
    fun invalidate(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    /** Signs out: revokes the app's access to the account's Drive. */
    suspend fun signOut(context: Context, account: String) = withContext(Dispatchers.IO) {
        runCatching {
            val token = tokenBlocking(context, account)
            invalidate(context, token)
            val c = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write("token=${URLEncoder.encode(token, "UTF-8")}".toByteArray()) }
            c.responseCode; c.disconnect()
        }
        Unit
    }
}
