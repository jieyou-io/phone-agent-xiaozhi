package com.xiaozhi.phoneagent.model

data class TaskMessage(
    val type: String = "task",
    val session_id: String,
    val task: String,
    val screenshot: String? = null,
    val translation_region: TranslationRegion? = null,
)

data class TranslationRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val screen_width: Int,
    val screen_height: Int,
)
