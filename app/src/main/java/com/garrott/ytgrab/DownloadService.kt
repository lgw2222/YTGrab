package com.garrott.ytgrab

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Runs downloads one at a time in the background, with a progress notification. */
class DownloadService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT = "format"
        const val ACTION_CANCEL = "com.garrott.ytgrab.CANCEL"
        private const val NOTIF_PROGRESS = 1
        private var doneNotifId = 1000

        fun enqueue(ctx: Context, url: String, format: Int) {
            val i = Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_FORMAT, format)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun openFile(ctx: Context, path: String) {
            val intent = viewIntent(ctx, path) ?: return
            try {
                ctx.startActivity(intent)
            } catch (_: Exception) {
            }
        }

        fun viewIntent(ctx: Context, path: String): Intent? {
            val f = File(path)
            if (!f.exists()) return null
            val uri: Uri = try {
                FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            } catch (_: Exception) {
                return null
            }
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(progressNotification("YTGrab", "Getting ready...", 0, true))

        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            main.postDelayed({ maybeStop() }, 500)
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            maybeStop()
            return START_NOT_STICKY
        }
        val format = intent?.getIntExtra(EXTRA_FORMAT, 0) ?: 0
        val job = Jobs.add(url, format)
        active.incrementAndGet()
        acquireWakeLock()
        executor.execute {
            try {
                runJob(job)
            } finally {
                if (active.decrementAndGet() == 0) main.post { maybeStop() }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIF_PROGRESS, n, type)
        } catch (_: Exception) {
        }
    }

    private fun maybeStop() {
        if (active.get() > 0) return
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelAll() {
        for (j in Jobs.list) {
            if (j.state == State.QUEUED) {
                j.state = State.CANCELED
                j.status = "Canceled"
            }
        }
        current?.let {
            it.state = State.CANCELED
            it.status = "Canceled"
            try {
                YoutubeDL.getInstance().destroyProcessById(it.processId)
            } catch (_: Exception) {
            }
        }
        Jobs.notifyChanged()
    }

    // ------------------------------------------------------------------ download
    private val destRe = Regex("""\[download] Destination: (.+)""")
    private val alreadyRe = Regex("""\[download] (.+) has already been downloaded""")
    private val mergeRe = Regex("""\[Merger] Merging formats into "(.+)"""")
    private val audioRe = Regex("""\[ExtractAudio] Destination: (.+)""")
    private val remuxRe = Regex("""\[VideoRemuxer] Remuxing video from \w+ to \w+; Destination: (.+)""")
    private val noRemuxRe = Regex("""\[VideoRemuxer] Not remuxing media file "(.+)";""")
    private val idSuffix = Regex("""\s\[[^\]]+]$""")

    private fun readLine(job: Job, raw: String) {
        val line = raw.trim()
        val path = destRe.find(line)?.groupValues?.get(1)
            ?: alreadyRe.find(line)?.groupValues?.get(1)
            ?: mergeRe.find(line)?.groupValues?.get(1)
            ?: audioRe.find(line)?.groupValues?.get(1)
            ?: remuxRe.find(line)?.groupValues?.get(1)
            ?: noRemuxRe.find(line)?.groupValues?.get(1)
        if (path != null) {
            job.filePath = path.trim()
            val name = File(path.trim()).nameWithoutExtension.replace(idSuffix, "")
            if (name.isNotBlank()) job.title = name
        }
        if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") || line.startsWith("[VideoRemuxer]")) {
            job.status = "Converting..."
        }
    }

    private fun runJob(job: Job) {
        if (job.state == State.CANCELED) return
        current = job
        job.state = State.RUNNING
        job.status = "Starting..."
        Jobs.notifyChanged()
        updateProgressNotif(job)

        if (!Engine.ensureInit(this)) {
            fail(job, Engine.status)
            return
        }

        val dir = Links.outputDir()
        dir.mkdirs()

        val req = YoutubeDLRequest(job.url)
        req.addOption("-o", File(dir, "%(title).120B [%(id)s].%(ext)s").absolutePath)
        req.addOption("--no-mtime")
        req.addOption("--no-playlist")
        req.addOption("--windows-filenames")
        req.addOption("--newline")
        Formats.apply(req, job.format)

        var lastUi = 0L
        val callback: (Float, Long, String) -> Unit = { progress, eta, line ->
            readLine(job, line)
            if (progress > 0f) job.progress = progress
            if (job.status != "Converting..." && progress > 0f) {
                val etaTxt = if (eta > 0) "  -  ${eta / 60}:${"%02d".format(eta % 60)} left" else ""
                job.status = "Downloading ${progress.toInt()}%$etaTxt"
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastUi > 400) {
                lastUi = now
                Jobs.notifyChanged()
                updateProgressNotif(job)
            }
        }

        try {
            val resp = YoutubeDL.getInstance().execute(req, job.processId, false, callback)
            resp.out.lines().forEach { readLine(job, it) }
            if (job.state == State.CANCELED) return
            job.state = State.DONE
            job.progress = 100f
            job.status = "Saved to Download/YTGrab  -  tap to open"
            job.filePath?.let { p ->
                if (File(p).exists()) MediaScannerConnection.scanFile(this, arrayOf(p), null, null)
            }
            Jobs.notifyChanged()
            doneNotification(job)
        } catch (e: Throwable) {
            if (job.state == State.CANCELED) {
                job.status = "Canceled"
                Jobs.notifyChanged()
            } else {
                fail(job, cleanError(e.message))
            }
        } finally {
            current = null
        }
    }

    private fun cleanError(msg: String?): String {
        if (msg.isNullOrBlank()) return "Something went wrong"
        val errLine = msg.lines().lastOrNull { it.contains("ERROR") } ?: msg.lines().last { it.isNotBlank() }
        return errLine.replace(Regex("""\u001B\[[0-9;]*m"""), "").replace("ERROR:", "").trim().take(160)
    }

    private fun fail(job: Job, why: String) {
        job.state = State.FAILED
        job.status = "Failed: $why"
        Jobs.notifyChanged()
    }

    // ------------------------------------------------------------------ notifications
    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun progressNotification(title: String, text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun updateProgressNotif(job: Job) {
        if (!canNotify()) return
        val queued = Jobs.list.count { it.state == State.QUEUED }
        val extra = if (queued > 0) "  (+$queued queued)" else ""
        val n = progressNotification(
            job.title.take(60), job.status + extra, job.progress.toInt(), job.progress <= 0f
        )
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_PROGRESS, n)
        } catch (_: SecurityException) {
        }
    }

    private fun doneNotification(job: Job) {
        if (!canNotify()) return
        val path = job.filePath
        val view = path?.let { viewIntent(this, it) }
        val pi = if (view != null) {
            PendingIntent.getActivity(
                this, job.id, view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else openAppIntent()
        val n = NotificationCompat.Builder(this, App.CH_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Downloaded")
            .setContentText(job.title)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(doneNotifId++, n)
        } catch (_: SecurityException) {
        }
    }

    // ------------------------------------------------------------------ wake lock
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTGrab:download").apply {
            setReferenceCounted(false)
            acquire(60L * 60 * 1000)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        executor.shutdownNow()
        super.onDestroy()
    }
}
