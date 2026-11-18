package dev.ahmedmohamed.hayaitts.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import co.touchlab.kermit.Logger

/**
 * Keeps the local API (127.0.0.1:11435) alive in the background so other apps, like Sherley, can
 * get speech with no start-up delay. Start it with an explicit intent to this class; stop it from
 * the notification.
 */
class HayaiApiService : Service() {

    private val log = Logger.withTag("HayaiApiService")
    private var server: HayaiApiServer? = null
    private var lastExit = ""

    override fun onCreate() {
        super.onCreate()
        lastExit = lastExitText()
        HayaiApiServer.lastExit = lastExit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = try {
        handle(intent)
    } catch (t: Throwable) {
        // Never let this service take the whole app down: show what went wrong in the notification instead.
        log.e(t) { "API service failed" }
        reportError(t)
        START_NOT_STICKY
    }

    private fun handle(intent: Intent?): Int {
        if (intent?.action == ACTION_STOP) {
            server?.stop(); server = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (server == null) {
            try {
                server = HayaiApiServer(SherpaTtsRuntime.get(applicationContext)).also { it.start() }
            } catch (t: Throwable) {
                log.e(t) { "could not start the API" }
                reportError(t)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /** Why the process died last time (native crash, low-memory kill...). Needs Android 11+. */
    private fun lastExitText(): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        return try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val e = am.getHistoricalProcessExitReasons(packageName, 0, 5).firstOrNull { it.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED && it.reason != android.app.ApplicationExitInfo.REASON_EXIT_SELF } ?: return ""
            val name = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "JAVA CRASH"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED: LOW MEMORY"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "KILLED: TOO MUCH RESOURCE USE"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "KILLED BY SIGNAL ${e.status}"
                android.app.ApplicationExitInfo.REASON_ANR -> "NOT RESPONDING"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY DIED"
                else -> "reason ${e.reason}"
            }
            val ago = (System.currentTimeMillis() - e.timestamp) / 60000
            "Last exit: $name ($ago min ago) ${e.description ?: ""}".trim().take(300)
        } catch (_: Throwable) { "" }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local voice API", NotificationManager.IMPORTANCE_MIN))
        }
        val stop = android.app.PendingIntent.getService(
            this, 0, Intent(this, HayaiApiService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("HayaiTTS local API")
            .setContentText("Serving speech on 127.0.0.1:${HayaiApiServer.PORT}")
            .setStyle(Notification.BigTextStyle().bigText("Serving speech on 127.0.0.1:${HayaiApiServer.PORT}\n" + lastExit))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stop,
                ).build(),
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, FGS_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
    }

    /** Shows the error as a normal notification (so it can be read and sent) and stops the service. */
    private fun reportError(t: Throwable) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ERR, "Local voice API errors", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val text = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(300) + " @ " +
                t.stackTrace.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ERR) else Notification.Builder(this)
            nm.notify(NOTIF_ERR_ID, b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("HayaiTTS local API could not start")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .build())
        }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        server?.stop(); server = null
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "hayai_api"
        const val CHANNEL_ERR = "hayai_api_err"
        const val NOTIF_ERR_ID = 4712
        const val NOTIF_ID = 4711
        const val ACTION_STOP = "dev.ahmedmohamed.hayaitts.API_STOP"
        const val FGS_TYPE_SPECIAL_USE = 0x40000000
    }
}
