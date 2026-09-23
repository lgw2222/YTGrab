package com.garrott.ytgrab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val ctx = applicationContext
        Thread {
            Engine.ensureInit(ctx)
            Engine.autoUpdate(ctx)
        }.start()
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
    }
}

/** Wraps the yt-dlp engine (Python + yt-dlp + ffmpeg bundled by youtubedl-android). */
object Engine {
    @Volatile
    var ready = false
        private set

    @Volatile
    var status: String = "Starting engine..."
        private set

    private val lock = Any()
    private const val PREFS = "ytgrab"
    private const val UPDATE_EVERY_MS = 12L * 60 * 60 * 1000

    fun ensureInit(ctx: Context): Boolean {
        synchronized(lock) {
            if (ready) return true
            return try {
                YoutubeDL.getInstance().init(ctx.applicationContext)
                FFmpeg.getInstance().init(ctx.applicationContext)
                ready = true
                status = "Engine ready - yt-dlp " + (YoutubeDL.getInstance().versionName(ctx) ?: "")
                Jobs.notifyChanged()
                true
            } catch (e: Throwable) {
                status = "Engine failed to start: ${e.message}"
                Jobs.notifyChanged()
                false
            }
        }
    }

    /** yt-dlp must stay current or sites break - update at most every 12h. */
    fun autoUpdate(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("lastUpdate", 0L)
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
        update(ctx)
    }

    fun update(ctx: Context): String {
        if (!ensureInit(ctx)) return status
        status = "Updating download engine..."
        Jobs.notifyChanged()
        status = try {
            val r = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("lastUpdate", System.currentTimeMillis()).apply()
            val v = YoutubeDL.getInstance().versionName(ctx) ?: ""
            if (r == YoutubeDL.UpdateStatus.DONE) "Engine updated - yt-dlp $v" else "Engine up to date - yt-dlp $v"
        } catch (e: Throwable) {
            "Engine update failed (check internet): ${e.message?.take(80)}"
        }
        Jobs.notifyChanged()
        return status
    }
}

object Formats {
    val labels = arrayOf(
        "Video - MP4 (best)",
        "Video - MP4 1080p",
        "Video - MP4 720p",
        "Video - MP4 480p",
        "Audio - MP3",
        "Audio - M4A"
    )

    fun apply(req: YoutubeDLRequest, index: Int) {
        when (index) {
            4 -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "320K")
                req.addOption("--embed-metadata")
            }
            5 -> {
                req.addOption("-f", "ba[ext=m4a]/ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "m4a")
                req.addOption("--embed-metadata")
            }
            else -> {
                val cap = when (index) {
                    1 -> "[height<=1080]"
                    2 -> "[height<=720]"
                    3 -> "[height<=480]"
                    else -> ""
                }
                req.addOption(
                    "-f",
                    "bv*${cap}[ext=mp4][vcodec^=avc]+ba[ext=m4a]/bv*${cap}[ext=mp4]+ba[ext=m4a]/bv*${cap}+ba/b${cap}/b"
                )
                req.addOption("--merge-output-format", "mp4")
                req.addOption("--remux-video", "mp4")
            }
        }
    }
}

enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELED }

class Job(val id: Int, val url: String, val format: Int) {
    @Volatile var title: String = url
    @Volatile var status: String = "Queued"
    @Volatile var progress: Float = 0f
    @Volatile var state: State = State.QUEUED
    @Volatile var filePath: String? = null
    val processId: String get() = "ytgrab-$id"
}

/** Shared list of downloads that the screen and the service both look at. */
object Jobs {
    val list = CopyOnWriteArrayList<Job>()
    private val nextId = AtomicInteger(1)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()

    fun add(url: String, format: Int): Job {
        val j = Job(nextId.getAndIncrement(), url, format)
        list.add(0, j)
        notifyChanged()
        return j
    }

    fun clearFinished() {
        list.removeAll { it.state == State.DONE || it.state == State.FAILED || it.state == State.CANCELED }
        notifyChanged()
    }

    fun addListener(r: Runnable) { listeners.add(r) }
    fun removeListener(r: Runnable) { listeners.remove(r) }

    fun notifyChanged() {
        main.post { listeners.forEach { it.run() } }
    }
}

object Links {
    private val URL_RE = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** Pulls the first link out of shared text like "Check this out https://x.com/...". */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        URL_RE.find(text)?.let { return it.value.trimEnd('.', ',', ')', ']') }
        val t = text.trim()
        if (!t.contains(' ') && t.contains('.') && t.contains('/')) return "https://$t"
        return null
    }

    @Suppress("DEPRECATION")
    fun outputDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTGrab")
}
