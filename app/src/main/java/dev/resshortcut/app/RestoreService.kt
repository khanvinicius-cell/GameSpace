package dev.resshortcut.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * Fica de olho no processo do jogo. Quando o jogo é encerrado de vez
 * (por exemplo, removido da lista de recentes), restaura a resolução.
 */
class RestoreService : Service() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_RESTORE_DISPLAY = "restore_display"
        private const val CHANNEL_ID = "restore_watch"
        private const val NOTIF_ID = 42
        private const val NOTIF_FAIL_ID = 43
        private const val PREFS = "restore_watch"
        private const val KEY_PKG = "pkg"
        private const val KEY_RESTORE_DISPLAY = "restore_display"
        private const val POLL_MS = 2000L
        private const val START_TIMEOUT_MS = 60_000L
        private const val CHECK_SECONDS = 5
    }

    @Volatile private var generation = 0
    @Volatile private var watcher: Process? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: prefs.getString(KEY_PKG, null)
        if (pkg == null || !ShizukuShell.isValidPackage(pkg)) {
            startForeground(NOTIF_ID, buildNotification("Nada para monitorar.", true))
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val restoreDisplay = intent?.getBooleanExtra(EXTRA_RESTORE_DISPLAY, true)
            ?: prefs.getBoolean(KEY_RESTORE_DISPLAY, true)
        prefs.edit().putString(KEY_PKG, pkg).putBoolean(KEY_RESTORE_DISPLAY, restoreDisplay).apply()
        startForeground(
            NOTIF_ID,
            buildNotification("Resolução será restaurada quando você fechar ${labelOf(pkg)}.", true)
        )
        val myGen = ++generation
        stopWatcher()
        Thread { monitor(pkg, myGen) }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        generation++
        stopWatcher()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        super.onDestroy()
    }

    private fun stopWatcher() {
        try { watcher?.destroy() } catch (_: Exception) {}
        watcher = null
    }

    /**
     * Em vez de consultar o jogo a cada poucos segundos, abre UM único processo de shell
     * que só espera o PID do jogo desaparecer (confere a cada 5 s, dentro do próprio shell).
     * O app fica parado, sem usar CPU, até o shell terminar.
     */
    private fun monitor(pkg: String, gen: Int) {
        val started = System.currentTimeMillis()
        var seen = false
        var misses = 0
        var errors = 0
        while (gen == generation) {
            try {
                val r = ShizukuShell.exec("pidof $pkg")
                val pid = if (r.code == 0) {
                    r.out.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
                } else null
                errors = 0
                if (pid == null) {
                    if (seen) {
                        misses++
                        if (misses >= 2) break
                    } else if (System.currentTimeMillis() - started > START_TIMEOUT_MS) {
                        break
                    }
                    Thread.sleep(if (seen) 1500L else 1000L)
                    continue
                }
                seen = true
                misses = 0
                val p = ShizukuShell.start("while [ -d /proc/$pid ]; do sleep $CHECK_SECONDS; done")
                watcher = p
                if (gen != generation) { p.destroy(); return }
                p.waitFor()
                watcher = null
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                errors++
                if (errors >= 10) {
                    fail("O Shizuku parou. Reinicie o Shizuku e toque em Restaurar resolução.")
                    return
                }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
            }
        }
        if (gen != generation) return
        restore(gen)
    }

    private fun restore(gen: Int) {
        var lastError = ""
        repeat(3) {
            if (gen != generation) return
            try {
                val watchPrefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (watchPrefs.getBoolean(KEY_RESTORE_DISPLAY, true)) DisplayControl.restore(this)
                toast("Resolução restaurada.")
                finish()
                return
            } catch (e: Exception) {
                lastError = e.message ?: "erro desconhecido"
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
            }
        }
        fail("Não consegui restaurar ($lastError). Toque para tentar de novo.")
    }

    private fun finish() {
        main.post {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        toast(message)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_FAIL_ID, buildNotification(message, false))
        finish()
    }

    private fun toast(s: String) = main.post {
        Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Restauração de resolução", NotificationManager.IMPORTANCE_LOW)
        )
        val resetIntent = Intent(this, LaunchShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("reset", true)
        }
        val pi = PendingIntent.getActivity(
            this, 0, resetIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_revert)
            .setContentTitle("Vyka R-L")
            .setContentText(text)
            .setOngoing(ongoing)
        if (ongoing) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_revert),
                    "Restaurar agora",
                    pi
                ).build()
            )
        } else {
            builder.setContentIntent(pi).setAutoCancel(true)
        }
        return builder.build()
    }
}
