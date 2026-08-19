package com.vidhi.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ApiMessage(val role: String, val content: String)
data class ChatResult(val reply: String, val provider: String)

class AiApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun chat(provider: String, language: String, history: List<ApiMessage>): ChatResult = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        history.takeLast(30).forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content.take(8000)))
        }

        val body = JSONObject()
            .put("provider", provider)
            .put("language", language)
            .put("messages", arr)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(ApiConfig.BASE_URL.trimEnd('/') + "/chat")
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Backend error ${response.code}"
                throw Exception(detail)
            }
            val json = JSONObject(text)
            ChatResult(
                reply = json.optString("reply", "Hmm, ek baar phir bolo?"),
                provider = json.optString("provider", provider)
            )
        }
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(ApiConfig.BASE_URL.trimEnd('/') + "/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
