package com.pixel10.ai.server

import com.google.gson.annotations.SerializedName

/**
 * Request/response models for the AI API.
 * Follows an OpenAI-compatible schema for easy integration.
 */

data class ChatRequest(
    val messages: List<Message> = emptyList(),
    val prompt: String? = null,
    val max_tokens: Int = 1024,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

data class Message(
    val role: String = "user",
    val content: String = ""
)

data class ChatResponse(
    val id: String,
    @SerializedName("object")
    val objectType: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "pixel10-on-device",
    val choices: List<Choice>,
    val usage: Usage
)

data class Choice(
    val index: Int = 0,
    val message: Message,
    val finish_reason: String = "stop"
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

data class StreamChunk(
    val id: String,
    @SerializedName("object")
    val objectType: String = "chat.completion.chunk",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "pixel10-on-device",
    val choices: List<StreamChoice>
)

data class StreamChoice(
    val index: Int = 0,
    val delta: Delta,
    val finish_reason: String? = null
)

data class Delta(
    val role: String? = null,
    val content: String? = null
)

data class ModelInfo(
    val id: String = "pixel10-on-device",
    @SerializedName("object")
    val objectType: String = "model",
    val owned_by: String = "local-device",
    val description: String = "On-device AI model running on Pixel 10 Tensor G5 chip"
)

data class ModelList(
    @SerializedName("object")
    val objectType: String = "list",
    val data: List<ModelInfo> = listOf(ModelInfo())
)

data class ErrorResponse(
    val error: ErrorDetail
)

data class ErrorDetail(
    val message: String,
    val type: String = "server_error",
    val code: Int = 500
)

data class ServerStatus(
    val status: String,
    val model: String,
    val device: String,
    val uptime_seconds: Long,
    val requests_served: Long,
    val endpoints: List<String> = listOf(
        "POST /v1/chat/completions",
        "POST /v1/completions",
        "GET  /v1/models",
        "GET  /health",
        "GET  /"
    )
)
