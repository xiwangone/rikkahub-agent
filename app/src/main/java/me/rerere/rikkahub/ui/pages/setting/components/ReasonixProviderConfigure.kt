package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ReasonixWebBridge
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.compose.koinInject

/**
 * Reasonix Provider 配置页。
 * - baseUrl：Reasonix serve 入口（:10002 nginx Basic Auth 或直连 :9899 token）
 * - username/password：nginx Basic Auth（与 reasonix-android 客户端一致）
 * - token：Reasonix serve token 模式（留空则走 Basic Auth）
 */
@Composable
fun ReasonixProviderConfigure(
    provider: ProviderSetting.Reasonix,
    onEdit: (ProviderSetting.Reasonix) -> Unit,
) {
    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && provider.baseUrl.toHttpUrlOrNull() == null,
    )

    OutlinedTextField(
        value = provider.username,
        onValueChange = { onEdit(provider.copy(username = it.trim())) },
        label = { Text("用户名（Basic Auth）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.password,
        onValueChange = { onEdit(provider.copy(password = it)) },
        label = { Text("密码（Basic Auth）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = null,
                )
            }
        },
    )

    var tokenVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.token,
        onValueChange = { onEdit(provider.copy(token = it.trim())) },
        label = { Text("Token（serve token 模式，可选）") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                Icon(
                    if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = null,
                )
            }
        },
    )

    // ── Web 桥设置（手机 Web 服务反向隧道到 ECS，供 reasonix 调用手机能力）──
    HorizontalDivider()
    Text(
        text = "Web 桥（反向隧道）",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.webBridgeEnabled,
            onCheckedChange = { onEdit(provider.copy(webBridgeEnabled = it)) },
        )
    }
    if (provider.webBridgeEnabled) {
        Text(
            text = "启动后自动：① 打开手机 Web 服务（:${provider.webBridgeLocalPort}）② 通过 SSH 反向隧道把手机端口映射到 ECS。切换 Reasonix 会话即自动连接。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = provider.webBridgeEcsHost,
            onValueChange = { onEdit(provider.copy(webBridgeEcsHost = it.trim())) },
            label = { Text("ECS 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = provider.webBridgeEcsUser,
            onValueChange = { onEdit(provider.copy(webBridgeEcsUser = it.trim())) },
            label = { Text("ECS 用户名（默认 root）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = provider.webBridgeEcsPort.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { onEdit(provider.copy(webBridgeEcsPort = it)) }
            },
            label = { Text("ECS SSH 端口") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = provider.webBridgeRemotePort.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { onEdit(provider.copy(webBridgeRemotePort = it)) }
            },
            label = { Text("ECS 侧隧道端口（reasonix 访问端口）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = provider.webBridgeLocalPort.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { onEdit(provider.copy(webBridgeLocalPort = it)) }
            },
            label = { Text("手机 Web 服务端口") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = provider.webBridgePrivateKeyPath,
            onValueChange = { onEdit(provider.copy(webBridgePrivateKeyPath = it.trim())) },
            label = { Text("SSH 私钥路径（可选，留空则需密码）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        val webBridge: ReasonixWebBridge = koinInject()
        val scope = rememberCoroutineScope()
        val bridgeState by webBridge.state.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    bridgeState.tunnelConnected -> "✅ 隧道已连接（ECS:${provider.webBridgeRemotePort} ← 手机:${provider.webBridgeLocalPort}）"
                    bridgeState.webServerRunning -> "⏳ Web 服务已启动，隧道连接中…"
                    else -> "隧道状态：未连接"
                },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (bridgeState.tunnelConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.weight(1f),
            )
        }
        if (bridgeState.message.isNotBlank()) {
            Text(
                text = bridgeState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Button(
                onClick = {
                    scope.launch {
                        webBridge.start(
                            ecsHost = provider.webBridgeEcsHost,
                            ecsPort = provider.webBridgeEcsPort,
                            ecsUser = provider.webBridgeEcsUser,
                            remoteTunnelPort = provider.webBridgeRemotePort,
                            localWebPort = provider.webBridgeLocalPort,
                            privateKeyPath = provider.webBridgePrivateKeyPath,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !bridgeState.tunnelConnected,
            ) {
                Text("启动 Web 桥")
            }
            androidx.compose.material3.OutlinedButton(
                onClick = { webBridge.stop() },
                modifier = Modifier.weight(1f),
                enabled = bridgeState.webServerRunning || bridgeState.tunnelConnected,
            ) {
                Text("停止")
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    Text(
        text = "Reasonix 会话由服务端管理（自动压缩/缓存优化继承）。" +
            "关闭本开关即继续使用原客户端，互不影响。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
