package com.example.countdown

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etSeconds: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private var lastGeneratedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSeconds = findViewById(R.id.etSeconds)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnDownload = findViewById(R.id.btnDownload)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        requestPermissions()

        btnGenerate.setOnClickListener { startGeneration() }
        btnDownload.setOnClickListener { downloadToTimerFolder() }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun startGeneration() {
        val text = etSeconds.text.toString().trim()
        val seconds = text.toIntOrNull()
        if (seconds == null || seconds <= 0) {
            Toast.makeText(this, "Enter a valid number of seconds", Toast.LENGTH_SHORT).show()
            return
        }

        btnGenerate.isEnabled = false
        btnDownload.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Generating video..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outFile = File(cacheDir, "countdown_${System.currentTimeMillis()}.mp4")
                VideoGenerator(this@MainActivity).generate(seconds, outFile) { p ->
                    runOnUiThread {
                        progressBar.progress = p
                        tvStatus.text = "Generating video... $p%"
                    }
                }
                lastGeneratedFile = outFile
                withContext(Dispatchers.Main) {
                    tvStatus.text = "✅ Video ready! Tap Download."
                    btnGenerate.isEnabled = true
                    btnDownload.isEnabled = true
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvStatus.text = "❌ Error: ${t.message}"
                    btnGenerate.isEnabled = true
                }
            }
        }
    }

    private fun downloadToTimerFolder() {
        val src = lastGeneratedFile ?: return
        if (!src.exists()) {
            Toast.makeText(this, "Source file missing", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Use app-specific external files dir -> /storage/emulated/0/Android/data/.../files/timer
            // Then also copy to public /storage/emulated/0/timer on older APIs.
            val appTimerDir = File(getExternalFilesDir(null), "timer").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val destApp = File(appTimerDir, "countdown_$stamp.mp4")
            copyFile(src, destApp)

            // Try public folder on pre-Q
            var publicPath = ""
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val publicDir = File(Environment.getExternalStorageDirectory(), "timer")
                publicDir.mkdirs()
                val destPublic = File(publicDir, "countdown_$stamp.mp4")
                copyFile(src, destPublic)
                publicPath = destPublic.absolutePath
            }

            val msg = buildString {
                append("Saved to:\n")
                append(destApp.absolutePath)
                if (publicPath.isNotEmpty()) append("\n$publicPath")
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            tvStatus.text = "✅ Downloaded to /timer folder"
        } catch (t: Throwable) {
            t.printStackTrace()
            Toast.makeText(this, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
    }
}
