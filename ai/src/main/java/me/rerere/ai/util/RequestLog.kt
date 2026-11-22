package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.BuildConfig

/**
 * 请求体可能包含用户提示词、历史消息和图片内容，因此任何构建类型都不记录原始 payload。
 * 保留调用点以避免改动 provider 请求流程；[body] 不会被执行或序列化。
 */
@Suppress("UNUSED_PARAMETER")
internal inline fun logRequestBody(tag: String, name: String, body: () -> String) {
    if (BuildConfig.DEBUG) Log.d(tag, "$name: request payload redacted")
}
