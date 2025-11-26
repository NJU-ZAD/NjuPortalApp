package com.example.njuportal

import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

object PortalClient {

    private val client = OkHttpClient()

    // ===========================
    // 登录
    // ===========================
    fun login(username: String, password: String, callback: (Boolean, String) -> Unit) {
        val url = "http://p2.nju.edu.cn/api/portal/v1/login"

        val json =
                """
            {
              "domain": "default",
              "username": "$username",
              "password": "$password"
            }
        """.trimIndent()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request)
                .enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                callback(false, "网络错误：${e.message}")
                            }

                            override fun onResponse(call: Call, response: Response) {
                                response.use {
                                    val text = it.body?.string().orEmpty()

                                    // 根据 NJU portal 的 JSON 返回判断成功/失败
                                    val (ok, msg) = parsePortalResponse(text)

                                    callback(ok, msg)
                                }
                            }
                        }
                )
    }

    // ===========================
    // 退出登录
    // ===========================
    fun logout(callback: (Boolean, String) -> Unit) {
        // 你原来用的接口，我先保留不动
        val url = "http://p2.nju.edu.cn/portal_io/logout"

        val request = Request.Builder().url(url).get().build()

        client.newCall(request)
                .enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                callback(false, "网络错误：${e.message}")
                            }

                            override fun onResponse(call: Call, response: Response) {
                                response.use {
                                    val text = it.body?.string().orEmpty()
                                    val result =
                                            try {
                                                val (ok, msg) = parsePortalResponse(text)
                                                ok to msg
                                            } catch (e: Exception) {
                                                val lower = text.lowercase()
                                                val ok =
                                                        lower.contains("logout") ||
                                                                lower.contains("log out")
                                                if (ok) {
                                                    true to "已退出登录"
                                                } else {
                                                    false to
                                                            if (text.isNotBlank()) text
                                                            else "退出登录失败（未知返回）"
                                                }
                                            }

                                    callback(result.first, result.second)
                                }
                            }
                        }
                )
    }

    private fun parsePortalResponse(resp: String): Pair<Boolean, String> {
        return try {
            val json = JSONObject(resp)

            val replyCode = json.optInt("reply_code", -1)
            val replyMsg = json.optString("reply_msg", "")

            // === 关键修改点 ===
            // 登录成功:  reply_code == 0
            // 退出成功:  reply_code == 101 (下线成功)
            val isSuccess = (replyCode == 0 || replyCode == 101)

            if (isSuccess) {
                // 成功场景：登录成功 / 下线成功
                val msg = if (replyMsg.isNotBlank()) replyMsg else "操作成功"
                true to msg
            } else {
                // 失败：从 results.io_reply_msg 里拿更详细的信息
                val results = json.optJSONObject("results")
                val detailMsg = results?.optString("io_reply_msg", "") ?: ""

                val finalMsg =
                        when {
                            detailMsg.isNotBlank() -> detailMsg // E012 未发现此用户 / E010 密码无效 等
                            replyMsg.isNotBlank() -> replyMsg // 登录失败 / 其它错误
                            else -> resp // 兜底：原始返回串
                        }

                false to finalMsg
            }
        } catch (e: Exception) {
            // 返回不是 JSON，就粗略判断一下
            if (resp.contains("成功")) {
                true to resp
            } else {
                false to resp.ifBlank { "操作失败（未知返回格式）" }
            }
        }
    }
}
