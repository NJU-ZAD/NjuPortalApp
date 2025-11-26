package com.example.njuportal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.net.wifi.WifiManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnLogout: Button
    private lateinit var txtStatus: TextView

    private val REQ_PERMISSIONS = 1001

    private var autoLoginAlreadyTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editUsername = findViewById(R.id.editUsername)
        editPassword = findViewById(R.id.editPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnLogout = findViewById(R.id.btnLogout)
        txtStatus = findViewById(R.id.txtStatus)

        ensurePermissions()

        btnLogin.setOnClickListener {
            doLogin(auto = false)
        }

        btnLogout.setOnClickListener {
            doLogout()
        }
    }

    override fun onResume() {
        super.onResume()

        // 每次从后台回来时检查一下：如果已经连上 NJU-WLAN，就自动认证
        if (hasLocationPermission()) {
            val ssid = getCurrentSsid()
            if (ssid == "NJU-WLAN") {
                txtStatus.text = "检测到已连接 NJU-WLAN，准备自动认证..."
                autoLoginIfPossible()
            }
        }
    }

    // ─────────────────────────────
    // 1. 只申请位置权限（用于读取 SSID）
    // ─────────────────────────────
    private fun ensurePermissions() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_PERMISSIONS
            )
        } else {
            afterPermissionsReady()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED

            if (granted) {
                afterPermissionsReady()
            } else {
                // 不强制，仍然可以让用户手动登录，只是无法判断是否在 NJU-WLAN
                txtStatus.text = "未授予位置权限，无法检测是否在 NJU-WLAN 下。请自行确认已连上校园网后再登录。"
                val (savedUser, savedPass) = Prefs.loadCredentials(this)
                savedUser?.let { editUsername.setText(it) }
                savedPass?.let { editPassword.setText(it) }
                if (!savedUser.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
                    setCredentialsEditable(false)
                }
            }
        }
    }

    private fun disableCopyOnEditText(edit: EditText) {
        edit.setTextIsSelectable(false)
        edit.isLongClickable = false
        edit.setOnLongClickListener { true }
        edit.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
            override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
            override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
        }
    }

    private fun enableCopyOnEditText(edit: EditText) {
        edit.setTextIsSelectable(true)
        edit.isLongClickable = true
        edit.setOnLongClickListener(null)
        edit.customSelectionActionModeCallback = null
    }

    // ─────────────────────────────
    // 2. 权限到位 → 检查 Wi-Fi + 自动认证 / 打开 Wi-Fi 面板
    // ─────────────────────────────
    private fun afterPermissionsReady() {
        // 预填已有的账号密码
        val (savedUser, savedPass) = Prefs.loadCredentials(this)
        savedUser?.let { editUsername.setText(it) }
        savedPass?.let { editPassword.setText(it) }

        // 如果有已保存账号，默认不允许编辑
        if (!savedUser.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
            setCredentialsEditable(false)
        }

        val ssid = getCurrentSsid()
        if (ssid == "NJU-WLAN") {
            txtStatus.text = "已连接 NJU-WLAN，准备自动认证..."
            autoLoginIfPossible()
        } else {
            txtStatus.text =
                "当前 Wi-Fi: ${ssid ?: "未知"}。请连接 NJU-WLAN 后再返回本应用。"
            showWifiHintDialog()
        }
    }

    private fun autoLoginIfPossible() {
        if (autoLoginAlreadyTried) return
        autoLoginAlreadyTried = true

        val (user, pass) = Prefs.loadCredentials(this)
        if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            doLogin(auto = true)
        } else {
            txtStatus.text = "已连接 NJU-WLAN，请输入用户名和密码后点击登录。"
        }
    }

    private fun setCredentialsEditable(editable: Boolean) {
        editUsername.isEnabled = editable
        editPassword.isEnabled = editable

        if (editable) {
            enableCopyOnEditText(editUsername)
            enableCopyOnEditText(editPassword)
        } else {
            disableCopyOnEditText(editUsername)
            disableCopyOnEditText(editPassword)
        }
    }

    // 辅助：是否在指定校园网下
    private fun isOnCampusWifi(): Boolean {
        val ssid = getCurrentSsid()
        return ssid == "NJU-WLAN"
    }

    // ─────────────────────────────
    // 3. 登录逻辑（增加 Wi-Fi 检查）
    // ─────────────────────────────
    private fun doLogin(auto: Boolean) {
        // ① 如果有定位权限，并且检测到不是 NJU-WLAN → 拦截
        if (hasLocationPermission() && !isOnCampusWifi()) {
            btnLogin.isEnabled = true
            txtStatus.text = "当前未连接 NJU-WLAN，无法认证。请先连接校园网。"
            if (!auto) {
                Toast.makeText(this, "请先连接 NJU-WLAN 后再登录", Toast.LENGTH_LONG).show()
                showWifiHintDialog()
            }
            return
        }

        // ② 如果没有定位权限：不强制拦截，只给个提醒
        if (!hasLocationPermission()) {
            txtStatus.text = "无法检测是否在 NJU-WLAN，下次可考虑授予位置权限。请确认已连上校园网后再登录。"
        }

        val username = editUsername.text.toString().trim()
        val password = editPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            if (!auto) Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
            return
        }

        btnLogin.isEnabled = false
        txtStatus.text = if (auto) "自动认证中..." else "正在认证..."

        PortalClient.login(username, password) { success, msg ->
            runOnUiThread {
                btnLogin.isEnabled = true
                txtStatus.text = msg

                if (success) {
                    Prefs.saveCredentials(this, username, password)
                    setCredentialsEditable(false)
                    if (auto) btnLogin.isEnabled = false
                    if (!auto) autoLoginAlreadyTried = false
                    Toast.makeText(
                        this,
                        if (auto) "自动认证成功！" else "认证成功！",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    if (!auto) {
                        setCredentialsEditable(true)
                        Toast.makeText(this, "认证失败：$msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ─────────────────────────────
    // 4. 退出登录（增加 Wi-Fi + 凭据检查）
    // ─────────────────────────────
    private fun doLogout() {
        // A. 有定位权限 + 检测到不是 NJU-WLAN → 不执行退出
        if (hasLocationPermission() && !isOnCampusWifi()) {
            txtStatus.text = "当前未连接 NJU-WLAN，无法退出登录。"
            Toast.makeText(this, "请在 NJU-WLAN 下再执行退出登录。", Toast.LENGTH_SHORT).show()
            return
        }

        // B. 如果没有定位权限：不拦截，只提醒一句
        if (!hasLocationPermission()) {
            txtStatus.text = "无法检测是否在 NJU-WLAN，将直接尝试退出登录，请确认已连上校园网。"
        }

        // C. 如果本地没有存储的账号密码，也不执行
        val (savedUser, savedPass) = Prefs.loadCredentials(this)
        if (savedUser.isNullOrEmpty() || savedPass.isNullOrEmpty()) {
            txtStatus.text = "未发现已保存的账号信息，无需退出。"
            return
        }

        btnLogout.isEnabled = false
        txtStatus.text = "正在退出登录..."

        PortalClient.logout { success, msg ->
            runOnUiThread {
                btnLogout.isEnabled = true

                if (success) {
                    Prefs.clearCredentials(this)
                    editUsername.setText("")
                    editPassword.setText("")
                    setCredentialsEditable(true)

                    btnLogin.isEnabled = true
                    autoLoginAlreadyTried = false

                    txtStatus.text = "已退出登录并清除账号密码。"
                    Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                } else {
                    txtStatus.text = "退出登录失败：$msg"
                    Toast.makeText(this, "退出登录失败：$msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─────────────────────────────
    // 5. 读取当前 SSID（可能拿不到就返回 null）
    // ─────────────────────────────
    @SuppressLint("MissingPermission")
    private fun getCurrentSsid(): String? {
        return try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return null
            val info = wifiManager.connectionInfo ?: return null
            info.ssid?.replace("\"", "")
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun showWifiHintDialog() {
        AlertDialog.Builder(this)
            .setTitle("连接 NJU-WLAN")
            .setMessage(
                "当前未连接 NJU-WLAN。\n\n" +
                "点击“去连接”将打开系统的 Wi-Fi 设置页面，请在其中选择并连接 NJU-WLAN，" +
                "然后返回本应用继续认证。"
            )
            .setPositiveButton("去连接") { _, _ ->
                openWifiPanel()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─────────────────────────────
    // 6. 打开系统 Wi-Fi 面板 / Wi-Fi 设置
    // ─────────────────────────────
    private fun openWifiPanel() {
        try {
            val intent = android.content.Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            startActivity(intent)
        } catch (e: Exception) {
            val intent = android.content.Intent(Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        }
    }
}
