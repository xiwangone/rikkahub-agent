package me.rerere.ai.provider.providers.reasonix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Reasonix SSE 客户端 — 连接 /events 端点，实时接收服务端推送的消息流。
 * 移植自 DeepSeek-Reasonix-android `ReasonixSseClient.kt`，增加认证头支持。
 *
 * [connect] 返回**热流**（MutableSharedFlow）：只建立一次 SSE 连接，
 * 多消费者共享同一事件流；多次 first()/collect 不会重建连接。
 * 这是支撑「turn_done 后短超时收尾」的关键——否则冷流每次 first() 都会重连。
 */
class ReasonixSseClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val token: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 单例热流：首次调用建立连接，后续调用复用同一事件流 */
    private val sharedFlow: MutableSharedFlow<SseEvent> by lazy {
        val flow = MutableSharedFlow<SseEvent>(extraBufferCapacity = 256)
        startConnect(flow)
        flow
    }

    fun connect(): Flow<SseEvent> = sharedFlow.asSharedFlow()

    private fun startConnect(destination: MutableSharedFlow<SseEvent>) {
        val request =
            Request.Builder()
                .url(baseUrl.toHttpUrl()!!.resolve("/events")!!)
                .header("Accept", "text/event-stream")
                .applyAuth()
                .build()

        val listener =
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    try {
                        val event = json.decodeFromString<SseEvent>(data)
                        destination.tryEmit(event)
                    } catch (_: Exception) {
                        // 解析失败则忽略
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    // 连接失败：不关闭流（长连接可能重连）；由调用方超时兜底
                }

                override fun onClosed(eventSource: EventSource) {
                    // 服务端关闭：保持流开放（下一条 submit 会触发新的连接）
                }
            }

        val factory = EventSources.createFactory(client)
        // 连接在独立线程建立，避免阻塞调用方
        Thread {
            runCatching {
                factory.newEventSource(request, listener)
            }
        }.start()
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        } else if (username.isNotBlank() || password.isNotBlank()) {
            header("Authorization", okhttp3.Credentials.basic(username, password))
        }
        return this
    }
}
