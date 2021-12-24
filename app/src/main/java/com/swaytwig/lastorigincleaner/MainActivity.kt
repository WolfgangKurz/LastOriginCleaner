package com.swaytwig.lastorigincleaner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private val switches: HashMap<Switch, String> = HashMap()
    private var grantedUri: MutableList<Uri> = mutableListOf() // Android 10 이상에서 사용할 권한 Uri 목록

    private val PREF_URI = "pref_uris"
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("GRANTED_URIS", Context.MODE_PRIVATE)
    }

    private val storages: List<String> =
        File("/storage").listFiles()?.filter { it.name != "self" }?.map { it.name } ?: listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uris = prefs.getString(PREF_URI, null)
        if (uris != null) {
            this.grantedUri = uris.split("\n")
                .map { Uri.parse(it) }
                .filter { it != null }
                .toMutableList()
        }

        this.checkPermission(true)

        switches.put(
            findViewById<Switch>(R.id.switch_filter_onestore),
            "Android/data/com.smartjoy.LastOrigin_C"
        )
        switches.put(
            findViewById<Switch>(R.id.switch_filter_playstore),
            "Android/data/com.smartjoy.LastOrigin_G"
        )
        switches.put(
            findViewById<Switch>(R.id.switch_filter_playstore_jp),
            "Android/data/com.pig.laojp.aos"
        )

        val label = findViewById<TextView>(R.id.tip_text)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            label.setText(R.string.tip_auto_selected)

            findViewById<TextView>(R.id.grant_description).visibility = View.GONE
            findViewById<Button>(R.id.button_grant).visibility = View.GONE
        } else
            label.setText(R.string.tip_saf_selected)

        this.updateSwitches()

        findViewById<Button>(R.id.button_clean).setOnClickListener { this.BeforeClean() }
        findViewById<Button>(R.id.button_grant).setOnClickListener {
            AlertDialog.Builder(this).apply {
                setTitle(R.string.GRANT_PERMISSION_TITLE)
                setMessage(R.string.GRANT_PERMISSION_MESSAGE)
                setPositiveButton(R.string.GRANT_PERMISSION_OK) { _, _ ->
                    val intent = Intent("android.intent.action.OPEN_DOCUMENT_TREE").apply {
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                            flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

                        putExtra("android.content.extra.SHOW_ADVANCED", true)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val uri = getSAFTreeUri()
                            putExtra(
                                "android.provider.extra.INITIAL_URI",
                                DocumentsContract.buildDocumentUriUsingTree(
                                    uri,
                                    DocumentsContract.getTreeDocumentId(uri)
                                )
                            )
                        }
                    }
                    startActivityForResult(intent, 44)
                }
            }
                .create()
                .show()
        }
    }

    // Granted 상황에 맞춰 사용 가능 갱신
    private fun updateSwitches() {
        for ((switch, path) in switches) {
            val usable = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> findMatchedUriFileList(
                    this,
                    "$path/files/UnityCache/Shared/",
                    this.grantedUri
                ) != null
                else -> File("/storage/emulated/0/$path/files/UnityCache/Shared/").exists()
            }

            switch.apply {
                isChecked = usable
                isEnabled = usable
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 44 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            if (!isRootUri(uri)) {
                Toast.makeText(this, R.string.GRANT_INVALID_PATH, Toast.LENGTH_SHORT).show()
                return
            }

            try {
                if (getSAFFileList(this, uri, "Android/data").isEmpty()) // 한 번 시도해서 권한이 있는지 체크
                    throw Exception("Failed to get Android/data list")
            } catch (e: Exception) {
                Toast.makeText(this, R.string.GRANT_FAILED, Toast.LENGTH_SHORT).show()
                return
            }

            if (!this.grantedUri.contains(uri)) {
                Toast.makeText(this, R.string.GRANT_SUCCESSFULLY, Toast.LENGTH_SHORT).show()
                this.grantedUri.add(uri)

                val pref = this.grantedUri
                    .map { it.toString() }
                    .joinToString("\n")
                prefs.edit().putString(PREF_URI, pref).apply()
            } else
                Toast.makeText(this, R.string.GRANT_ALREADY_GRANTED, Toast.LENGTH_SHORT).show()

            this.updateSwitches()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun Log(text: String) {
        CoroutineScope(Main).launch {
            val el = findViewById<TextView>(R.id.textLog)
            el.text = text + "\n" + el.text.toString()
        }
    }

    private fun BeforeClean() {
        // 혹시 전처리가 필요하면 여기 추가하면 됨
        CoroutineScope(Default).launch { this@MainActivity.DoClean() }
    }

    private fun DoClean() {
        val cleanBtn = findViewById<Button>(R.id.button_clean)
        CoroutineScope(Main).launch { cleanBtn.isEnabled = false }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            CleanerSAF(this, this.grantedUri, this.switches) { s -> this.Log(s) }
        else
            Cleaner(this, this.storages, this.switches) { s -> this.Log(s) }

        CoroutineScope(Main).launch { cleanBtn.isEnabled = true }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0)
            this.checkPermission(false)
    }

    private fun checkPermission(need_request: Boolean) {
        val cleanBtn = findViewById<Button>(R.id.button_clean)

        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            permissions.any { p ->
                ContextCompat.checkSelfPermission(
                    this,
                    p
                ) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            cleanBtn.isEnabled = false
            if (need_request)
                this.requestPermission(permissions);
        } else
            cleanBtn.isEnabled = true

        this.updateSwitches()
    }

    private fun requestPermission(permissions: Array<String>) {
        // Toast.makeText(this, "앱 실행을 위해서는 저장소 권한을 설정해야합니다", Toast.LENGTH_SHORT).show()
        // ActivityCompat.requestPermissions(this, permissions, 0)
        ActivityCompat.requestPermissions(this, permissions, 0)
        this.checkPermission(false)

        if (permissions.any { p ->
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    p
                )
            }) {
            Toast.makeText(
                this,
                "앱 실행을 위해서는 저장소 권한을 설정해야합니다\n앱 정보에서 수동으로 설정해주시기 바랍니다",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}