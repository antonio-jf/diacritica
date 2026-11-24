package app.diacritica.grammar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.diacritica.DiacriticaApp
import app.diacritica.MainActivity
import app.diacritica.R
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The optional daily notification for Lesson of the day (off by default): once a day around the time you
 * chose, with today's topic. Tapping it opens the lesson. Runs with WorkManager, so it works with the app closed.
 */
object DailyNotifier {
    private const val WORK = "daily-lesson-notification"
    private const val CHANNEL = "daily_lesson"
    const val EXTRA_TOPIC = "app.diacritica.DAILY_TOPIC"

    /** Schedules (or cancels) the notification to match the current setting. */
    fun schedule(context: Context) {
        val daily = (context.applicationContext as DiacriticaApp).container.daily
        val wm = WorkManager.getInstance(context)
        if (!daily.notifyOn) { wm.cancelUniqueWork(WORK); return }
        val at = daily.notifyAt
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(at / 60, at % 60)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes().coerceAtLeast(1)
        val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()
        // UPDATE keeps one job and moves it to the new time.
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun show(context: Context, topic: DailyTopic) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lesson of the day", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TOPIC, topic.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_lesson)
            .setContentTitle("Lesson of the day · ${topic.lang.nativeName}")
            .setContentText(topic.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${topic.title}\n${topic.pattern}"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(4711, n) }
    }
}

class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val daily = (applicationContext as DiacriticaApp).container.daily
        if (!daily.notifyOn) return Result.success()
        daily.today()?.let { DailyNotifier.show(applicationContext, it) }
        return Result.success()
    }
}
