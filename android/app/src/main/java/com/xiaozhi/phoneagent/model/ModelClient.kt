package com.xiaozhi.phoneagent.model

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ModelClient(
    private val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    private val apiKey: String,
    private val modelName: String = "autoglm-phone"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson().newBuilder().setLenient().create()
    private val conversationHistory = mutableListOf<Message>()
    private val maxHistorySize = 10

    fun reset() {
        conversationHistory.clear()
    }

    suspend fun sendRequest(
        task: String,
        screenshot: Bitmap,
        currentApp: String,
        systemPrompt: String
    ): ModelResponse = withContext(Dispatchers.IO) {
        val imageBase64 = bitmapToBase64(screenshot)

        val isFirstStep = conversationHistory.isEmpty()
        if (isFirstStep) {
            conversationHistory.add(Message("system", systemPrompt))
        }

        // Prune old messages to prevent memory growth (keep system + last N messages)
        while (conversationHistory.size > maxHistorySize) {
            if (conversationHistory.size > 1) {
                conversationHistory.removeAt(1) // Keep system prompt at index 0
            }
        }

        val screenInfo = """{"current_app": "$currentApp"}"""
        val userText = if (isFirstStep) {
            "$task\n\n$screenInfo"
        } else {
            "** Screen Info **\n\n$screenInfo"
        }

        val userContent = listOf(
            ContentPart("image_url", imageUrl = ImageUrl("data:image/png;base64,$imageBase64")),
            ContentPart("text", text = userText)
        )

        conversationHistory.add(Message("user", content = userContent))

        val requestBody = ChatRequest(
            model = modelName,
            messages = conversationHistory,
            maxTokens = 3000,
            temperature = 0.0f,
            topP = 0.85f,
            frequencyPenalty = 0.2f
        )

        val jsonBody = gson.toJson(requestBody)
//        Log.d(TAG, "Request URL: $baseUrl/chat/completions")
//        Log.d(TAG, "Request Model: $modelName")
//        Log.d(TAG, "Sending request to model: ${conversationHistory.size} messages")

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")

            Log.d(TAG, "Response received: ${response.code}")
            Log.d(TAG, "Raw response body: $responseBody") // Log the raw response for debugging

            if (!response.isSuccessful) {
                // Remove the failed user message
                conversationHistory.removeLastOrNull()
                throw Exception("API error: ${response.code}, Body: $responseBody")
            }

            val rawContent = extractContentFromResponse(responseBody)
            val modelResponse = parseResponse(rawContent)
            
            // Format assistant response for context to enforce schema in future turns
            // Matching: f"<think>{response.thinking}</think><answer>{response.action}</answer>"
            val formattedAssistantContent = "<think>${modelResponse.thinking}</think><answer>${rawContent.substringAfter("</think>").substringAfter("<answer>").substringBeforeLast("</answer>").ifEmpty { rawContent }}</answer>"
            // Actually Python's response.action is the action STRING.
            // Let's use a simpler approach that matches Python's logic
            val actionPart = when {
                rawContent.contains("finish(message=") -> "finish(message=" + rawContent.split("finish(message=", limit = 2)[1]
                rawContent.contains("do(action=") -> "do(action=" + rawContent.split("do(action=", limit = 2)[1]
                rawContent.contains("<answer>") -> rawContent.split("<answer>", limit = 2)[1].replace("</answer>", "").trim()
                else -> rawContent
            }
            val assistantContext = "<think>${modelResponse.thinking}</think><answer>$actionPart</answer>"

            conversationHistory.add(Message("assistant", assistantContext))

            // Remove images from old messages to save tokens (matching Python implementation)
            // Find the last user message and remove its image content
            for (i in conversationHistory.indices.reversed()) {
                if (conversationHistory[i].role == "user") {
                    conversationHistory[i] = removeImagesFromMessage(conversationHistory[i])
                    break
                }
            }

            modelResponse
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            // Remove the failed user message if it was added
            if (conversationHistory.lastOrNull()?.role == "user") {
                conversationHistory.removeLastOrNull()
            }
            ModelResponse(
                thinking = "",
                action = null,
                rawContent = "",
                error = e.message
            )
        }
    }

    private fun parseResponse(content: String): ModelResponse {
        val thinking: String
        val actionStr: String

        when {
            content.contains("finish(message=") -> {
                val parts = content.split("finish(message=", limit = 2)
                thinking = parts[0].trim()
                actionStr = "finish(message=" + parts[1]
            }
            content.contains("do(action=") -> {
                val parts = content.split("do(action=", limit = 2)
                thinking = parts[0].trim()
                actionStr = "do(action=" + parts[1]
            }
            content.contains("<answer>") -> {
                val parts = content.split("<answer>", limit = 2)
                thinking = parts[0].replace("<think>", "").replace("</think>", "").trim()
                actionStr = parts[1].replace("</answer>", "").trim()
            }
            else -> {
                thinking = ""
                actionStr = content
            }
        }

        val action = parseAction(actionStr)
        return ModelResponse(
            thinking = thinking,
            action = action,
            rawContent = content,
            error = null
        )
    }

    private fun parseAction(actionStr: String): Action? {
        return try {
            when {
                actionStr.startsWith("finish(message=") -> {
                    val message = actionStr
                        .removePrefix("finish(message=\"")
                        .removeSuffix("\")")
                        .removeSuffix(")")
                    Action.Finish(message)
                }
                actionStr.startsWith("do(action=") -> {
                    parseDoAction(actionStr)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse action: $actionStr", e)
            null
        }
    }

    private fun parseDoAction(actionStr: String): Action.Do? {
        val actionType = Regex("""action="([^"]+)"""").find(actionStr)?.groupValues?.get(1) ?: return null

        return when (actionType) {
            "Tap" -> {
                val element = parseElement(actionStr)
                if (element != null) Action.Do.Tap(element.first, element.second) else null
            }
            "Swipe" -> {
                val start = parseCoordinates(actionStr, "start")
                val end = parseCoordinates(actionStr, "end")
                if (start != null && end != null) {
                    Action.Do.Swipe(start.first, start.second, end.first, end.second)
                } else null
            }
            "Type" -> {
                val text = Regex("""text="([^"]+)"""").find(actionStr)?.groupValues?.get(1) ?: ""
                Action.Do.Type(text)
            }
            "Type_Name" -> {
                val text = Regex("""text="([^"]+)"""").find(actionStr)?.groupValues?.get(1) ?: ""
                Action.Do.Type(text)
            }
            "Launch" -> {
                val app = Regex("""app="([^"]+)"""").find(actionStr)?.groupValues?.get(1) ?: ""
                Action.Do.Launch(app)
            }
            "Back" -> Action.Do.Back
            "Home" -> Action.Do.Home
            "Wait" -> {
                val duration = Regex("""duration="([^"]+)"""").find(actionStr)?.groupValues?.get(1) ?: "1 seconds"
                val seconds = duration.replace("seconds", "").trim().toFloatOrNull() ?: 1f
                Action.Do.Wait(seconds)
            }
            "Long Press" -> {
                val element = parseElement(actionStr)
                if (element != null) Action.Do.LongPress(element.first, element.second) else null
            }
            "Double Tap" -> {
                val element = parseElement(actionStr)
                if (element != null) Action.Do.DoubleTap(element.first, element.second) else null
            }
            else -> null
        }
    }

    private fun parseElement(str: String): Pair<Int, Int>? {
        val regex = Regex("""element=\[(\d+),\s*(\d+)]""")
        val match = regex.find(str) ?: return null
        return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun parseCoordinates(str: String, name: String): Pair<Int, Int>? {
        val regex = Regex("""$name=\[(\d+),\s*(\d+)]""")
        val match = regex.find(str) ?: return null
        return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun extractContentFromResponse(responseBody: String): String {
        if (responseBody.contains("data:")) {
            val streamingContent = parseStreamingContent(responseBody)
            if (streamingContent.isNullOrBlank()) {
                throw Exception("SSE response contained no valid content")
            }
            return streamingContent
        }

        val concatenatedContent = parseConcatenatedChunks(responseBody)
        if (!concatenatedContent.isNullOrBlank()) {
            return concatenatedContent
        }

        val chatResponse = parseFirstChatResponse(responseBody)
            ?: throw Exception("Unable to parse JSON response")
        return chatResponse.choices.firstOrNull()?.message?.content
            ?: throw Exception("No content in response")
    }

    private fun parseConcatenatedChunks(responseBody: String): String? {
        val jsonObjects = extractAllJsonObjects(responseBody)
        if (jsonObjects.size <= 1) return null

        val contentBuilder = StringBuilder()
        for (jsonStr in jsonObjects) {
            try {
                val chunk = gson.fromJson(jsonStr, StreamChatResponse::class.java)
                val firstChoice = chunk.choices.firstOrNull()
                val chunkContent = firstChoice?.delta?.content
                if (!chunkContent.isNullOrEmpty()) {
                    contentBuilder.append(chunkContent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse concatenated chunk", e)
            }
        }
        return contentBuilder.toString().ifEmpty { null }
    }

    private fun extractAllJsonObjects(text: String): List<String> {
        val result = mutableListOf<String>()
        var pos = 0
        while (pos < text.length) {
            val start = text.indexOf('{', pos)
            if (start == -1) break

            var depth = 0
            var inString = false
            var escape = false
            var end = -1

            for (i in start until text.length) {
                val c = text[i]
                when {
                    escape -> escape = false
                    c == '\\' && inString -> escape = true
                    c == '"' && !escape -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }

            if (end != -1) {
                result.add(text.substring(start, end + 1))
                pos = end + 1
            } else {
                break
            }
        }
        return result
    }

    private fun parseFirstChatResponse(responseBody: String): ChatResponse? {
        val jsonStr = extractFirstJsonObject(responseBody)
        if (jsonStr != null) {
            try {
                return gson.fromJson(jsonStr, ChatResponse::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse extracted JSON", e)
            }
        }
        responseBody.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                try {
                    return gson.fromJson(line, ChatResponse::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse JSONL line", e)
                }
            }
        return null
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' && !escape -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseStreamingContent(responseBody: String): String? {
        if (!responseBody.contains("data:")) {
            return null
        }
        val contentBuilder = StringBuilder()
        val lines = responseBody.split("\n")
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.startsWith("data:")) continue

            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue

            try {
                val chunk = gson.fromJson(payload, StreamChatResponse::class.java)
                val firstChoice = chunk.choices.firstOrNull()
                val chunkContent = firstChoice?.let { choice ->
                    choice.delta?.content ?: choice.message?.content
                }
                if (!chunkContent.isNullOrEmpty()) {
                    contentBuilder.append(chunkContent)
                }
                if (firstChoice?.finishReason == "stop") break
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse SSE chunk: $payload", e)
            }
        }
        return contentBuilder.toString().ifEmpty { null }
    }

    private fun removeImagesFromMessage(message: Message): Message {
        // If content is a List<ContentPart>, filter out image_url parts
        // This saves tokens by keeping only text in historical messages
        val content = message.content
        if (content is List<*>) {
            val contentList = content.filterIsInstance<ContentPart>()
            val textOnly = contentList.filter { it.type == "text" }
            return Message(message.role, textOnly.ifEmpty { null })
        }
        return message
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 直接使用原图进行压缩发送
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 将图片按比例缩放到指定最大宽度
     * 如果图片宽度小于等于最大宽度，则返回原图
     */
    private fun scaleToMaxWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth) {
            Log.d(TAG, "Image size OK: ${width}x${height}")
            return bitmap
        }

        val scale = maxWidth.toFloat() / width
        val newWidth = maxWidth
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Scaling image from ${width}x${height} to ${newWidth}x${newHeight}")

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    companion object {
        private const val TAG = "ModelClient"
        // 发送给模型的图片最大宽度，提高到 1440px 以获得更清晰的分析结果
        private const val MAX_IMAGE_WIDTH = 1440
    }
}

// Request/Response data classes
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int,
    val temperature: Float,
    @SerializedName("top_p") val topP: Float,
    @SerializedName("frequency_penalty") val frequencyPenalty: Float
)

data class Message(
    val role: String,
    val content: Any? = null
)

data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null
)

data class ImageUrl(val url: String)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: ResponseMessage
)

data class ResponseMessage(
    val content: String
)

data class StreamChatResponse(
    val choices: List<StreamChoice>
)

data class StreamChoice(
    val delta: ResponseDelta?,
    val message: ResponseMessage?,
    @SerializedName("finish_reason") val finishReason: String?
)

data class ResponseDelta(
    val content: String?
)

data class ModelResponse(
    val thinking: String,
    val action: Action?,
    val rawContent: String,
    val error: String?
)

sealed class Action {
    data class Finish(val message: String) : Action()

    sealed class Do : Action() {
        data class Tap(val x: Int, val y: Int) : Do()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Do()
        data class Type(val text: String) : Do()
        data class Launch(val app: String) : Do()
        object Back : Do()
        object Home : Do()
        data class Wait(val seconds: Float) : Do()
        data class LongPress(val x: Int, val y: Int) : Do()
        data class DoubleTap(val x: Int, val y: Int) : Do()
    }
}
