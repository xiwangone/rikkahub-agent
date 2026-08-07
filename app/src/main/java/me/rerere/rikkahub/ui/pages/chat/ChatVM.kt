package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.AutoTaskConfig
import me.rerere.rikkahub.ui.components.ai.MAX_AUTO_TASK_TRIGGER_COUNT
import me.rerere.rikkahub.ui.components.ai.writeAutoTaskConfig
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    // 会话级 token 累计（当前分支所有消息 usage 之和），供聊天底部统计条展示
    val sessionTotals: StateFlow<TokenBudgetTracker.Totals> =
        conversation
            .map { TokenBudgetTracker.aggregate(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TokenBudgetTracker.aggregate(conversation.value))
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 自动任务调度 Job
    private var autoTaskJob: Job? = null

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs =
        chatService
            .getConversationJobs()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        cancelAutoTask()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch =
        settings
            .map {
                it.getCurrentAssistant().enableWebSearch
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel =
        settings
            .map { settings ->
                settings.getCurrentChatModel()
            }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job =
        viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(
        oldSettings: Settings,
        newSettings: Settings,
    ) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(
        assistant: Assistant,
        model: Model,
    ) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants =
                        settings.assistants.map {
                            if (it.id == assistant.id) {
                                it.copy(
                                    chatModelId = model.id,
                                )
                            } else {
                                it
                            }
                        },
                )
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(
        content: List<UIMessagePart>,
        answer: Boolean = true,
    ) {
        if (content.isEmptyInputMessage()) return

        // 自动压缩检查：开关开启时，达到触发点（当前模式阈值）即弹窗询问
        if (answer) {
            val s = settings.value
            if (s.autoCompressEnabled) {
                viewModelScope.launch { maybeAutoCompress(s) }
            }
        }

        chatService.sendMessage(_conversationId, content, answer)
    }

    /**
     * 会话级自动压缩触发点（绝对 token 数），null 表示尚未初始化（首次检查时懒初始化）。
     * 触发后由用户决定：确认压缩 → 回到阈值；取消 → 延后到「当前累计值 + 阈值」。
     */
    var autoCompressNextTriggerAt by mutableStateOf<Long?>(null)

    /** 自动压缩触发确认弹窗：达到触发点时置 true，由 ChatPage 弹出「是否确认压缩？」 */
    var pendingAutoCompressConfirm by mutableStateOf(false)

    /** 百分比模式基准未设置时沿用的默认 context 估算（实测 DeepSeek V4 可承载 439.6K，按 512K 估算） */
    private val defaultAutoCompressBase: Long = 512 * 1024

    /** 当前模式的触发阈值（绝对 token 数）：模式A = 基准×百分比/100，模式B = token 消耗上限 */
    private fun autoCompressTriggerPoint(s: Settings): Long =
        when (s.autoCompressMode) {
            1 -> {
                s.autoCompressTokenLimit
            }

            else -> {
                val base = if (s.autoCompressTokenBase > 0) s.autoCompressTokenBase else defaultAutoCompressBase
                base * s.autoCompressThreshold.coerceIn(50, 95) / 100
            }
        }

    /** 当前模式的累计检测值：模式A = 估算 token（文本字符数 1:1），模式B = 会话累计 totalTokens */
    private fun autoCompressCurrentValue(): Long =
        when (settings.value.autoCompressMode) {
            1 -> {
                sessionTotals.value.totalTokens
            }

            else -> {
                conversation.value.currentMessages
                    .sumOf { it.toText().length }
                    .toLong()
            }
        }

    /**
     * 自动压缩检查：当前检测值超过会话级触发点（nextTriggerAt）时，
     * 不直接压缩，而是置 [pendingAutoCompressConfirm] 由 ChatPage 弹窗询问用户。
     * 如果当前正在生成（AI 流式回复中），则等待生成结束后再弹窗，
     * 避免确认弹窗盖在回复流上。
     */
    private suspend fun maybeAutoCompress(s: Settings) {
        // 模式B：未设置累计上限（0 = 不启用）时不触发
        if (s.autoCompressMode == 1 && s.autoCompressTokenLimit <= 0) return
        // 首次检查：触发点 = 当前模式阈值
        if (autoCompressNextTriggerAt == null) {
            autoCompressNextTriggerAt = autoCompressTriggerPoint(s)
        }
        val current = autoCompressCurrentValue()
        if (current > autoCompressNextTriggerAt!!) {
            // 生成中不弹窗，延后到生成结束再弹（最多等 5 分钟防死锁）
            withTimeoutOrNull(300_000L) {
                conversationJob.first { it == null || !it.isActive }
            }
            pendingAutoCompressConfirm = true
        }
    }

    /** 确认压缩：压缩对话，累计随上下文归零（聚合值自然回落），触发点回到阈值 */
    fun confirmAutoCompress() {
        pendingAutoCompressConfirm = false
        viewModelScope.launch {
            chatService
                .compressConversation(
                    _conversationId,
                    conversation.value,
                    additionalPrompt = "",
                    targetTokens = 2000,
                    keepRecentMessages = 32,
                ).onFailure {
                    chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
                }
            autoCompressNextTriggerAt = autoCompressTriggerPoint(settings.value)
        }
    }

    /** 取消压缩：延后触发点 = 当前累计值 + 阈值（累计统计不清零） */
    fun cancelAutoCompress() {
        autoCompressNextTriggerAt = autoCompressCurrentValue() + autoCompressTriggerPoint(settings.value)
        pendingAutoCompressConfirm = false
    }

    /** 用户重设阈值/模式：触发点 = 当前累计值 + 新阈值 */
    fun resetAutoCompressTriggerPoint(newSettings: Settings) {
        autoCompressNextTriggerAt = autoCompressCurrentValue() + autoCompressTriggerPoint(newSettings)
    }

    fun handleMessageEdit(
        parts: List<UIMessagePart>,
        messageId: Uuid,
    ) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ): Job =
        viewModelScope.launch {
            chatService
                .compressConversation(
                    _conversationId,
                    conversation.value,
                    additionalPrompt,
                    targetTokens,
                    keepRecentMessages,
                ).onFailure {
                    chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
                }
        }

    suspend fun forkMessage(message: UIMessage): Conversation =
        chatService.forkConversationAtMessage(_conversationId, message.id)

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException(context.getString(R.string.chat_stop_generation_before_delete)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation),
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        scope: me.rerere.rikkahub.service.ChatService.ApprovalScope =
            me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
        toolName: String? = null,
    ) {
        chatService.handleToolApproval(
            conversationId = _conversationId,
            toolCallId = toolCallId,
            approved = approved,
            reason = reason,
            scope = scope,
            toolName = toolName,
        )
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(
        conversation: Conversation,
        targetAssistantId: Uuid,
    ) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // Folders are per-assistant groupings; after switching assistant the old folder is
            // not visible under the new one, so clear the assignment to avoid losing the chat.
            val updatedConversation =
                conversationFull.copy(
                    assistantId = targetAssistantId,
                    folderId = null,
                )
            // Drop any "Allow for this chat" grants the user gave the previous assistant.
            // The grants apply to a tool surface the new assistant may use very differently
            // (different prompt, different tool list), and the user authorised them under
            // the old persona's behaviour, not this one's. Persistent "Always Allow" grants
            // stay (they were granted globally) but ChatScope is reset.
            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                .clearChat(conversation.id)
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(
        message: UIMessage,
        targetLanguage: Locale,
    ) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(
        conversation: Conversation,
        force: Boolean = false,
    ) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node,
                    ),
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes =
                        currentConversation.messageNodes.map { existingNode ->
                            if (existingNode.id == node.id) {
                                existingNode.copy(isFavorite = !currentlyFavorited)
                            } else {
                                existingNode
                            }
                        },
                )
            }
        }
    }

    /**
     * 调度自动任务：根据配置的触发模式启动定时器或空闲监听。
     *  - 模式 0（可触发次数）：会话空闲时自动发送，达到设置次数或次数上限后自动停止
     *  - 模式 1（定时触发）：会话空闲达设定秒数后自动发送一次，触发后清除（一次性触发）
     */
    fun scheduleAutoTask(config: AutoTaskConfig) {
        cancelAutoTask()

        if (config.mode == 0 && config.triggerCount <= 0) return
        if (config.mode == 1 && config.intervalSeconds <= 0) return

        autoTaskJob =
            viewModelScope.launch {
                when (config.mode) {
                    0 -> {
                        // 可触发次数：等待会话空闲后自动发送，到达设置次数或次数上限（100）后自动停止触发
                        val limit = config.triggerCount.coerceIn(1, MAX_AUTO_TASK_TRIGGER_COUNT)
                        var triggered = 0
                        while (triggered < limit && isActive) {
                            // 等待当前生成中的回复完成
                            val job = conversationJob.value
                            if (job != null && job.isActive) {
                                try {
                                    withTimeoutOrNull(300_000L) {
                                        conversationJob.first { it == null || !it.isActive }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            // 短暂冷却，避免上一轮回复刚结束立即连发
                            delay(1_000L)
                            if (!isActive) break
                            handleMessageSend(
                                listOf(UIMessagePart.Text(config.message)),
                                answer = true,
                            )
                            triggered++
                        }
                        writeAutoTaskConfig(context, AutoTaskConfig())
                    }

                    1 -> {
                        // 定时触发：监听会话空闲状态，空闲达设定秒数后自动发送（原不定时逻辑）
                        while (isActive) {
                            val job = conversationJob.value
                            if (job != null && job.isActive) {
                                try {
                                    withTimeoutOrNull(300_000L) {
                                        conversationJob.first { it == null || !it.isActive }
                                    }
                                } catch (_: Exception) {
                                }
                            }

                            var idleSeconds = 0
                            while (idleSeconds < config.intervalSeconds && isActive) {
                                delay(1_000L)
                                val currentJob = conversationJob.value
                                if (currentJob != null && currentJob.isActive) {
                                    idleSeconds = 0
                                    break
                                }
                                idleSeconds++
                            }

                            if (idleSeconds >= config.intervalSeconds && isActive) {
                                handleMessageSend(
                                    listOf(UIMessagePart.Text(config.message)),
                                    answer = true,
                                )
                                writeAutoTaskConfig(context, AutoTaskConfig())
                                break
                            }
                        }
                    }
                }
            }
    }

    /**
     * 取消当前调度的自动任务。
     */
    fun cancelAutoTask() {
        autoTaskJob?.cancel()
        autoTaskJob = null
    }
}
