package app.diacritica.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.diacritica.BuildConfig
import app.diacritica.DiacriticaApp
import app.diacritica.MainActivity
import app.diacritica.R

/**
 * Two jobs:
 * - [ACTION_RESULT]: PackageInstaller's answer to an update. Shows Android's confirmation screen when it
 *   still needs one (first self-install, Android 11 and older), or reports a failure.
 * - MY_PACKAGE_REPLACED: the app was just updated (and closed for it). If Diacritica started that update,
 *   a notification offers to open the new version.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESULT -> result(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> replaced(context)
        }
    }

    private fun result(context: Context, intent: Intent) {
        val updater = (context.applicationContext as DiacriticaApp).container.updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { updater.installEnded("Android didn't show the install screen. Try again."); return }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { updater.installEnded("Couldn't open the install screen. Try again.") }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The app is being replaced; MY_PACKAGE_REPLACED follows.
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.installEnded(null) // Cancelled: back to Install.
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                updater.installEnded(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "Android refused the update (it doesn't match the installed app). Install it from the GitHub page."
                        else -> "The install didn't finish" + (why?.let { ": $it" } ?: ".")
                    },
                )
            }
        }
    }

    private fun replaced(context: Context) {
        val updater = (context.applicationContext as DiacriticaApp).container.updater
        if (updater.takeInstalledVersion() == 0) return // Updated some other way (Android Studio, file manager).
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setContentTitle("Diacritica updated")
            .setContentText("Version ${BuildConfig.VERSION_NAME} is installed. Tap to open.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        try { NotificationManagerCompat.from(context).notify(4712, n) } catch (_: SecurityException) {}
    }

    companion object {
        const val ACTION_RESULT = "app.diacritica.UPDATE_INSTALL_RESULT"
        private const val CHANNEL = "app_updates"
    }
}
