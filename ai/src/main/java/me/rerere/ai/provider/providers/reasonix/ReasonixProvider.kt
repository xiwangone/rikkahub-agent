package me.rerere.ai.provider.providers.reasonix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessagePart.Tool
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.Uuid.Companion.parse

/**
 * Reasonix Provider — RikkaHub 直连 Reasonix serve（阶段5融合）。
 *
 * 架构：RikkaHub 作为 Reasonix 的「远程 UI」——会话由服务端管理（历史/压缩/checkpoint
 * 全部继承），每次对话开始 POST /new，streamText 只发增量（最后一条用户消息）→ POST /submit，
 * 然后监听 GET /events SSE 事件流并映射为 MessageChunk。
 *
 * SSE 事件映射：
 * - text        → UIMessagePart.Text
 * - reasoning   → UIMessagePart.Reasoning
 * - tool_dispatch/tool_result → UIMessagePart.Tool
 * - usage       → TokenUsage
 * - turn_done   → finishReason=stop，结束流
 */
class ReasonixProvider(
    private val clientFactory: (ProviderSetting.Reasonix) -> ReasonixApi,
) : Provider<ProviderSetting.Reasonix> {

    constructor() : this({ setting ->
        ReasonixApi(
            baseUrl = setting.baseUrl,
            username = setting.username,
            password = setting.password,
            token = setting.token,
        )
    })

    private fun api(setting: ProviderSetting.Reasonix): ReasonixApi = clientFactory(setting)

    override suspend fun listModels(providerSetting: ProviderSetting.Reasonix): List<Model> {
        val models = api(providerSetting).getModels()
        if (models.isEmpty()) {
            // 无法拉取时给一个默认占位（Reasonix 默认模型）
            return listOf(defaultModel())
        }
        return models.mapIndexed { index, info ->
            Model(
                modelId = info.ref.ifBlank { info.model },
                displayName = info.model.ifBlank { info.ref },
                id = Uuid.random(),
                type = ModelType.CHAT,
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                contextLength = 1_000_000,
            )
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Reasonix,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val parts = mutableListOf<UIMessagePart>()
        var usage: TokenUsage? = null
        var lastToolName = ""
        var lastToolId = ""

        streamText(providerSetting, messages, params).collect { chunk ->
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta?.parts?.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> parts.add(part)
                        is UIMessagePart.Reasoning -> parts.add(part)
                        is Tool -> {
                            // 合并同名工具调用：先骨架后结果
                            if (part.isExecuted) {
                                parts.add(part)
                            } else {
                                lastToolName = part.toolName
                                lastToolId = part.toolCallId
                            }
                        }
                        else -> {}
                    }
                }
            }
            usage = chunk.usage ?: usage
        }

        // 有工具调用但无最终文本时，追加一个工具占位展示
        if (parts.none { it is Tool } && lastToolName.isNotBlank()) {
            parts.add(
                Tool(
                    toolCallId = lastToolId,
                    toolName = lastToolName,
                    input = "",
                    approvalState = ToolApprovalState.Auto,
                )
            )
        }

        return MessageChunk(
            id = UUID.randomUUID().toString(),
            model = params.model.modelId,
            choices =
                listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                        finishReason = "stop",
                    ),
                ),
            usage = usage,
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by Reasonix")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Reasonix,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        val api = api(providerSetting)
        // 会话：当前无历史时新建会话（服务端管会话；本 Provider 单会话场景）
        val lastUserInput = messages.lastOrNull { it.role == MessageRole.USER }?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("") { it.text }
            ?: return@flow

        // 必须先 POST /new（新建会话）+ POST /submit（提交增量输入），
        // 服务端才会开始生成并向 /events 推送；否则两端干等（App 无限转圈）。
        api.newSession()
        api.submit(lastUserInput)

        // 通过 SSE 建立连接后提交增量输入
        val sse = ReasonixSseClient(
            baseUrl = providerSetting.baseUrl,
            username = providerSetting.username,
            password = providerSetting.password,
            token = providerSetting.token,
        )
        val events = sse.connect()
        var toolAccumulator: MutableList<UIMessagePart.Tool>? = null
        var usage: TokenUsage? = null
        var textAccumulator = StringBuilder()
        var reasoningAccumulator = StringBuilder()
        var turnDone = false

        events.collect { event ->
            when (event.kind) {
                "text" -> {
                    val t = event.text ?: return@collect
                    textAccumulator.append(t)
                    emit(
                        chunk(
                            providerSetting,
                            params,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Text(text = t)),
                            ),
                            usage = usage,
                        )
                    )
                }
                "reasoning" -> {
                    val r = event.reasoning ?: return@collect
                    reasoningAccumulator.append(r)
                    emit(
                        chunk(
                            providerSetting,
                            params,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Reasoning(reasoning = r)),
                            ),
                            usage = usage,
                        )
                    )
                }
                "tool_dispatch" -> {
                    val tool = event.tool
                    if (tool != null) {
                        toolAccumulator = toolAccumulator ?: mutableListOf()
                        toolAccumulator!!.add(
                            Tool(
                                toolCallId = tool.id,
                                toolName = tool.name,
                                input = tool.args ?: tool.arguments ?: "",
                                approvalState = ToolApprovalState.Auto,
                            )
                        )
                        emit(
                            chunk(
                                providerSetting,
                                params,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        Tool(
                                            toolCallId = tool.id,
                                            toolName = tool.name,
                                            input = tool.args ?: tool.arguments ?: "",
                                            approvalState = ToolApprovalState.Auto,
                                        )
                                    ),
                                ),
                                usage = usage,
                            )
                        )
                    }
                }
                "tool_result" -> {
                    val tool = event.tool
                    if (tool != null) {
                        val output = tool.output ?: tool.err ?: ""
                        emit(
                            chunk(
                                providerSetting,
                                params,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        Tool(
                                            toolCallId = tool.id,
                                            toolName = tool.name,
                                            input = tool.args ?: tool.arguments ?: "",
                                            output = listOf(UIMessagePart.Text(text = output)),
                                            approvalState = ToolApprovalState.Auto,
                                        )
                                    ),
                                ),
                                usage = usage,
                            )
                        )
                    }
                }
                "usage" -> {
                    val u = event.usage
                    if (u != null) {
                        usage =
                            TokenUsage(
                                promptTokens = u.promptTokens.toInt(),
                                completionTokens = u.completionTokens.toInt(),
                                cachedTokens = u.cacheHitTokens.toInt(),
                                totalTokens = u.totalTokens.toInt(),
                                cost = u.costUsd,
                            )
                    }
                }
                "turn_done" -> {
                    turnDone = true
                    emit(
                        chunk(
                            providerSetting,
                            params,
                            delta = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
                            usage = usage,
                            finishReason = "stop",
                        )
                    )
                    return@collect
                }
                else -> {}
            }
        }
        if (!turnDone) {
            // events 流结束未收到 turn_done，补一个结束帧
            emit(
                chunk(
                    providerSetting,
                    params,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
                    usage = usage,
                    finishReason = "stop",
                )
            )
        }
    }

    private fun defaultModel(): Model =
        Model(
            modelId = "deepseek-v4-flash",
            displayName = "DeepSeek V4 Flash (default)",
            type = ModelType.CHAT,
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )

    private fun chunk(
        providerSetting: ProviderSetting.Reasonix,
        params: TextGenerationParams,
        delta: UIMessage,
        usage: TokenUsage?,
        finishReason: String? = null,
    ): MessageChunk =
        MessageChunk(
            id = UUID.randomUUID().toString(),
            model = params.model.modelId,
            choices =
                listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = delta,
                        message = null,
                        finishReason = finishReason,
                    ),
                ),
            usage = usage,
        )
}
