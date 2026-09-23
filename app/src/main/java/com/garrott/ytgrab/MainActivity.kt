package com.garrott.ytgrab

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var formatSpinner: Spinner
    private lateinit var autoSwitch: MaterialSwitch
    private lateinit var jobList: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var engineText: TextView

    private class Row(val root: View, val title: TextView, val status: TextView, val bar: ProgressBar)
    private val rows = HashMap<Int, Row>()
    private val listener = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        formatSpinner = findViewById(R.id.formatSpinner)
        autoSwitch = findViewById(R.id.autoSwitch)
        jobList = findViewById(R.id.jobList)
        emptyText = findViewById(R.id.emptyText)
        engineText = findViewById(R.id.engineText)

        val prefs = Prefs(this)
        formatSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Formats.labels
        )
        formatSpinner.setSelection(prefs.format.coerceIn(0, Formats.labels.size - 1))
        formatSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.format = pos
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        autoSwitch.isChecked = prefs.autoStart
        autoSwitch.setOnCheckedChangeListener { _, checked -> prefs.autoStart = checked }

        findViewById<MaterialButton>(R.id.pasteBtn).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            val link = Links.extract(text)
            if (link != null) urlInput.setText(link) else toast("No link on your clipboard")
        }
        findViewById<MaterialButton>(R.id.downloadBtn).setOnClickListener {
            val link = Links.extract(urlInput.text?.toString())
            if (link == null) {
                toast("Paste a video link first")
            } else {
                startDownload(link)
            }
        }
        findViewById<MaterialButton>(R.id.updateBtn).setOnClickListener {
            toast("Updating engine...")
            val ctx = applicationContext
            Thread {
                val msg = Engine.update(ctx)
                runOnUiThread { toast(msg) }
            }.start()
        }
        findViewById<MaterialButton>(R.id.clearBtn).setOnClickListener { Jobs.clearFinished() }

        askPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val shared = intent?.getStringExtra(EXTRA_PREFILL) ?: return
        urlInput.setText(shared)
        intent?.removeExtra(EXTRA_PREFILL)
    }

    private fun startDownload(link: String) {
        DownloadService.enqueue(this, link, formatSpinner.selectedItemPosition)
        urlInput.setText("")
        toast("Downloading...")
    }

    override fun onStart() {
        super.onStart()
        Jobs.addListener(listener)
        refresh()
    }

    override fun onStop() {
        Jobs.removeListener(listener)
        super.onStop()
    }

    // ------------------------------------------------------------------ list UI
    private fun refresh() {
        engineText.text = Engine.status
        val jobs = Jobs.list.toList()
        emptyText.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE

        val ids = jobs.map { it.id }.toSet()
        rows.keys.filter { it !in ids }.forEach { id -> rows.remove(id)?.let { jobList.removeView(it.root) } }

        jobs.forEachIndexed { index, job ->
            val row = rows.getOrPut(job.id) { makeRow() }
            if (jobList.indexOfChild(row.root) != index) {
                jobList.removeView(row.root)
                jobList.addView(row.root, index)
            }
            row.title.text = job.title
            row.status.text = job.status
            row.bar.isIndeterminate = job.state == State.RUNNING && job.progress <= 0f
            row.bar.progress = job.progress.toInt()
            row.bar.visibility = if (job.state == State.RUNNING || job.state == State.QUEUED) View.VISIBLE else View.GONE
            val color = when (job.state) {
                State.DONE -> 0xFF2E7D32.toInt()
                State.FAILED -> 0xFFC62828.toInt()
                else -> row.title.currentTextColor
            }
            row.status.setTextColor(color)
            row.root.setOnClickListener {
                val p = job.filePath
                if (job.state == State.DONE && p != null) DownloadService.openFile(this, p)
                else if (job.state == State.FAILED) toast(job.status)
            }
        }
    }

    private fun makeRow(): Row {
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val title = TextView(this).apply {
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            maxLines = 2
        }
        val status = TextView(this).apply {
            textSize = 13f
            alpha = 0.85f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        root.addView(title)
        root.addView(status)
        root.addView(bar)
        return Row(root, title, status, bar)
    }

    // ------------------------------------------------------------------ misc
    private fun askPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 7)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PREFILL = "prefill"
    }
}

/** Receives "Share -> YTGrab" from YouTube/X/Instagram etc. Starts the download and disappears. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val link = Links.extract(text)
        val prefs = Prefs(this)
        when {
            link == null -> Toast.makeText(this, "YTGrab: no link found in what you shared", Toast.LENGTH_SHORT).show()
            prefs.autoStart -> {
                DownloadService.enqueue(this, link, prefs.format)
                Toast.makeText(this, "YTGrab: downloading ${Formats.labels[prefs.format.coerceIn(0, Formats.labels.size - 1)]}", Toast.LENGTH_SHORT).show()
            }
            else -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PREFILL, link)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("ytgrab", Context.MODE_PRIVATE)
    var format: Int
        get() = p.getInt("format", 0)
        set(v) { p.edit().putInt("format", v).apply() }
    var autoStart: Boolean
        get() = p.getBoolean("autoStart", true)
        set(v) { p.edit().putBoolean("autoStart", v).apply() }
}
