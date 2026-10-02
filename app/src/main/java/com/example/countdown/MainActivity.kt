package com.example.countdown

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSeconds = findViewById(R.id.etSeconds)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnDownload = findViewById(R.id.btnDownload)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
            }
        }

        btnGenerate.setOnClickListener { startGeneration() }
        btnDownload.setOnClickListener { downloadToTimerFolder() }
    }

    private fun startGeneration() {
        val seconds = etSeconds.text.toString().toIntOrNull()
        if (seconds == null || seconds <= 0) {
            Toast.makeText(this, "Enter a valid number of seconds (e.g., 60)", Toast.LENGTH_SHORT).show()
            return
        }

        btnGenerate.isEnabled = false
        btnDownload.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Generating video (0%)..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outFile = File(cacheDir, "countdown_temp.mp4")
                VideoGenerator(this@MainActivity).generate(seconds, outFile) { p ->
                    runOnUiThread {
                        progressBar.progress = p
                        tvStatus.text = "Generating video ($p%)..."
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission required to save video", Toast.LENGTH_SHORT).show()
                return
            }
        }

        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "countdown_$stamp.mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/timer")
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it).use { outputStream ->
                        FileInputStream(src).use { inputStream ->
                            inputStream.copyTo(outputStream!!)
                        }
                    }
                    Toast.makeText(this, "✅ Saved to Movies/timer/$fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val publicDir = File(Environment.getExternalStorageDirectory(), "timer")
                if (!publicDir.exists()) publicDir.mkdirs()
                
                val dest = File(publicDir, fileName)
                FileInputStream(src).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Toast.makeText(this, "✅ Saved to /storage/emulated/0/timer/$fileName", Toast.LENGTH_LONG).show()
            }
            tvStatus.text = "✅ Downloaded to /timer folder"
        } catch (t: Throwable) {
            t.printStackTrace()
            Toast.makeText(this, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
