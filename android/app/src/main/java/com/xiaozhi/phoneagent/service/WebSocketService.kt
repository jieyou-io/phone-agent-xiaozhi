package com.xiaozhi.phoneagent.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.xiaozhi.phoneagent.model.TaskMessage
import com.xiaozhi.phoneagent.utils.HttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class WebSocketService(
    private val onOpen: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onEvent: (String, JsonObject) -> Unit,
    private val onError: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val gson = Gson()
    private var webSocket: WebSocket? = null

    fun connect(url: String) {
        val wsUrl = normalizeUrl(url)
        if (wsUrl.isBlank()) {
            onError("Empty URL")
            return
        }
        val request = Request.Builder().url(wsUrl).build()
        webSocket = HttpClient.get().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = parseMessage(text)
                if (parsed != null) {
                    onEvent(parsed.first, parsed.second)
                    onMessage(formatMessage(parsed.first, parsed.second))
                } else {
                    onMessage(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t.message ?: "Unknown error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed()
            }
        })
    }

    fun sendTask(task: TaskMessage) {
        val payload = gson.toJson(task)
        webSocket?.send(payload)
    }

    fun sendBind(deviceId: String, sessionId: String) {
        val obj = JsonObject()
        obj.addProperty("type", "bind")
        obj.addProperty("device_id", deviceId)
        obj.addProperty("session_id", sessionId)
        webSocket?.send(obj.toString())
    }

    fun sendRaw(payload: String) {
        webSocket?.send(payload)
    }

    fun disconnect() {
        webSocket?.close(1000, "client close")
        webSocket = null
    }

    private fun normalizeUrl(url: String): String {
        if (url.startsWith("ws://") || url.startsWith("wss://")) return url
        if (url.startsWith("http://")) return "ws://" + url.removePrefix("http://")
        if (url.startsWith("https://")) return "wss://" + url.removePrefix("https://")
        return url
    }

    private fun parseMessage(raw: String): Pair<String, JsonObject>? {
        return try {
            val obj = gson.fromJson(raw, JsonObject::class.java)
            val type = obj.get("type")?.asString ?: return null
            type to obj
        } catch (ex: Exception) {
            null
        }
    }

    private fun formatMessage(type: String, obj: JsonObject): String {
        return when (type) {
            "effect" -> "Effect: ${obj.get("effects")}"
            "action" -> "Action: ${obj.get("action")}"
            else -> obj.toString()
        }
    }
}
