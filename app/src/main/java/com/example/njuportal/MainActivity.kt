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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnLogout: Button
    private lateinit var txtStatus: TextView

    private val REQ_PERMISSIONS = 1001

    // 防止自动登录重复触发
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

        // 只有在「有定位权限 + 能读到 SSID 且确实是 NJU-WLAN」的情况下，才做传统自动登录
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
                // 情况 ②：用户拒绝定位权限
                txtStatus.text =
                    "未授予位置权限，无法检测是否在 NJU-WLAN 下。\n" +
                    "将尝试检测是否需要自动认证。"

                val (savedUser, savedPass) = Prefs.loadCredentials(this)
                savedUser?.let { editUsername.setText(it) }
                savedPass?.let { editPassword.setText(it) }
                if (!savedUser.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
                    setCredentialsEditable(false)
                }

                // 无法检测 Wi-Fi 场景下的自动登录逻辑
                maybeAutoLoginWithoutWifiCheck()
            }
        }
    }

    private fun disableCopyOnEditText(edit: EditText) {
        edit.setTextIsSelectable(false)
        edit.isLongClickable = false
        edit.setOnLongClickListener { true }
        edit.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean = false

            override fun onPrepareActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode?,
                item: android.view.MenuItem?
            ): Boolean = false

            override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
        }
    }

    private fun enableCopyOnEditText(edit: EditText) {
        edit.setTextIsSelectable(true)
        edit.isLongClickable = true
        edit.setOnLongClickListener(null)
        edit.customSelectionActionModeCallback = null
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

    // ─────────────────────────────
    // 2. 权限到位 → 检查 Wi-Fi + 自动认证 / 无法检测时走特殊逻辑
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

        when {
            ssid == "NJU-WLAN" -> {
                txtStatus.text = "已连接 NJU-WLAN，准备自动认证..."
                autoLoginIfPossible()
            }

            // 能读到 SSID 且不是 NJU-WLAN → 正常提示去切 Wi-Fi
            ssid != null -> {
                txtStatus.text =
                    "当前 Wi-Fi: $ssid。请连接 NJU-WLAN 后再返回本应用。"
                showWifiHintDialog()
            }

            // 情况 ①：有权限但拿不到 SSID（例如系统位置开关没开）
            else -> {
                txtStatus.text =
                    "无法检测当前 Wi-Fi 是否为 NJU-WLAN，" +
                    "将尝试检测是否需要自动认证..."
                maybeAutoLoginWithoutWifiCheck()
            }
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

    // ─────────────────────────────
    // 3. 无法检测 Wi-Fi 场景下：是否需要自动登录？
    // ─────────────────────────────
    private fun enterManualConfirmMode(message: String? = null) {
        txtStatus.text = message ?: (
            "无法自动检测网络状态。\n" +
            "请确认已连接 NJU-WLAN 后，手动点击“登录校园网”或“退出登录”。"
        )
    }

    private fun checkInternetReachable(callback: (Boolean) -> Unit) {
        Thread {
            var ok = false
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://gitee.com")
                    .head()
                    .build()
                val response = client.newCall(request).execute()
                ok = response.isSuccessful
                response.close()
            } catch (_: Exception) {
                ok = false
            }
            runOnUiThread { callback(ok) }
        }.start()
    }

    /**
     * 情况 ① / ②：无法可靠检测当前是否为 NJU-WLAN 时调用：
     * - 有保存的凭据 + 外网不可达 → 尝试自动登录一次
     * - 其他情况 → 直接进入“用户确认 Wi-Fi 后手动登录/登出”的状态
     */
    private fun maybeAutoLoginWithoutWifiCheck() {
        val (user, pass) = Prefs.loadCredentials(this)

        // 没有保存凭据 → 直接让用户手动处理
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            enterManualConfirmMode(
                "无法检测当前 Wi-Fi 是否为 NJU-WLAN。\n" +
                "请确认已连接 NJU-WLAN 后，输入账号密码并点击“登录校园网”。"
            )
            return
        }

        // 已经尝试过自动登录 → 不再重复自动登录
        if (autoLoginAlreadyTried) {
            enterManualConfirmMode(
                "无法检测当前 Wi-Fi 是否为 NJU-WLAN。\n" +
                "请确认已连接 NJU-WLAN 后，手动点击“登录校园网”或“退出登录”。"
            )
            return
        }

        autoLoginAlreadyTried = true
        txtStatus.text = "正在检测网络连通性..."

        checkInternetReachable { reachable ->
            if (reachable) {
                // 外网本来就通，不需要自动登录
                enterManualConfirmMode(
                    "当前网络似乎已可访问外网，但无法确定是否在 NJU-WLAN。\n" +
                    "如仍无法上网，请确认已连接 NJU-WLAN 后，再手动点击“登录校园网”。"
                )
            } else {
                // 外网不可达 → 尝试自动登录一次
                txtStatus.text = "检测到外网不可用，尝试自动认证..."
                editUsername.setText(user)
                editPassword.setText(pass)
                doLogin(auto = true)
                // 如果自动登录失败，doLogin 内部会给出“请确认 Wi-Fi 后手动登录”的提示
            }
        }
    }

    // ─────────────────────────────
    // 4. 登录逻辑（含 Wi-Fi 检查 + 自动登录失败后的提示）
    // ─────────────────────────────
    private fun doLogin(auto: Boolean) {
        val ssid = getCurrentSsid()
        val canCheckWifi = hasLocationPermission() && ssid != null

        // 能确定 SSID 且不是 NJU-WLAN → 拦截
        if (canCheckWifi && ssid != "NJU-WLAN") {
            btnLogin.isEnabled = true
            txtStatus.text = "当前 Wi-Fi：$ssid，非 NJU-WLAN，无法认证。请先连接校园网。"
            if (!auto) {
                Toast.makeText(this, "请先连接 NJU-WLAN 后再登录", Toast.LENGTH_LONG).show()
                showWifiHintDialog()
            }
            return
        }

        // 无法确定 SSID（情况 ① / ②）→ 提醒用户自己确认 Wi-Fi
        if (!canCheckWifi) {
            txtStatus.text =
                "无法检测当前 Wi-Fi 是否为 NJU-WLAN。\n" +
                "请确认已连接校园网后再登录。"
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
                    if (auto) {
                        // 自动登录失败 → 直接引导用户确认 Wi-Fi 后手动登录
                        txtStatus.text =
                            "自动认证失败：$msg\n\n" +
                            "请确认已连接 NJU-WLAN，然后手动点击“登录校园网”。"
                    } else {
                        setCredentialsEditable(true)
                        Toast.makeText(this, "认证失败：$msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ─────────────────────────────
    // 5. 退出登录（Wi-Fi 检查 + 凭据检查）
    // ─────────────────────────────
    private fun doLogout() {
        val ssid = getCurrentSsid()
        val canCheckWifi = hasLocationPermission() && ssid != null

        // 能确定 SSID 且不是 NJU-WLAN → 不执行退出
        if (canCheckWifi && ssid != "NJU-WLAN") {
            txtStatus.text = "当前 Wi-Fi：$ssid，非 NJU-WLAN，无法退出登录。"
            Toast.makeText(this, "请在 NJU-WLAN 下再执行退出登录。", Toast.LENGTH_SHORT).show()
            return
        }

        // 无法确定 SSID → 提醒用户自己确认 Wi-Fi，但继续尝试退出
        if (!canCheckWifi) {
            txtStatus.text =
                "无法检测当前 Wi-Fi 是否为 NJU-WLAN，将直接尝试退出登录。\n" +
                "请确认已连接 NJU-WLAN。"
        }

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
    // 6. 读取当前 SSID（<unknown ssid> 视为无法检测）
    // ─────────────────────────────
    @SuppressLint("MissingPermission")
    private fun getCurrentSsid(): String? {
        return try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return null
            val info = wifiManager.connectionInfo ?: return null
            val raw = info.ssid ?: return null
            val cleaned = raw.replace("\"", "")

            if (cleaned.equals("<unknown ssid>", ignoreCase = true) || cleaned.isEmpty()) {
                null
            } else {
                cleaned
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
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
    // 7. 打开系统 Wi-Fi 面板 / Wi-Fi 设置
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
