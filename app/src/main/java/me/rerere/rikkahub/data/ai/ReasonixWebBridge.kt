package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.WebServerService

/**
 * Reasonix Web 桥 — 把 RikkaHub 手机端 Web 服务反向隧道到 ECS，供 reasonix serve/run 访问。
 *
 * 原理：手机主动出站 SSH 到 ECS（阿里云公网可达），建立反向隧道
 *   `ssh -R <remotePort>:localhost:<localPort> root@<ECS>`，
 *   ECS 上 reasonix 即可通过 `http://127.0.0.1:<remotePort>` 访问手机 Web API。
 *
 * 生命周期：
 * - [start]：启动 Web 服务 + 建立反向隧道（前台服务持有，防止被杀）
 * - [stop]：断开隧道 + 停止 Web 服务
 * - 状态通过 [state] 暴露（用于配置页展示）
 */
class ReasonixWebBridge(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sshSession: Session? = null
    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    data class BridgeState(
        val webServerRunning: Boolean = false,
        val tunnelConnected: Boolean = false,
        val message: String = "",
    )

    /**
     * 启动 Web 桥：
     * 1. 启动手机 Web 服务（复用 WebServerService，端口取设置，默认 8080）
     * 2. 建立 SSH 反向隧道（JSch 保持连接）
     */
    suspend fun start(
        ecsHost: String,
        ecsPort: Int = 22,
        ecsUser: String = "root",
        remoteTunnelPort: Int = 8080,
        localWebPort: Int = 8080,
        privateKeyPath: String = "",
        password: String = "",
    ): Boolean = withContext(Dispatchers.Default) {
        // 1. 启动 Web 服务（前台服务，通知常驻）
        runCatching {
            val intent =
                android.content.Intent(context, WebServerService::class.java)
                    .setAction(WebServerService.ACTION_START)
                    .putExtra(WebServerService.EXTRA_PORT, localWebPort)
                    .putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, false)
            context.startForegroundService(intent)
        }.onSuccess {
            _state.value = _state.value.copy(webServerRunning = true)
        }.onFailure { e ->
            Log.e(TAG, "Failed to start web server", e)
            _state.value = _state.value.copy(message = "Web 服务启动失败: ${e.message}")
        }

        // 2. 建立 SSH 反向隧道
        val ok = connectTunnel(
            ecsHost = ecsHost,
            ecsPort = ecsPort,
            ecsUser = ecsUser,
            remoteTunnelPort = remoteTunnelPort,
            localWebPort = localWebPort,
            privateKeyPath = privateKeyPath,
            password = password,
        )
        _state.value = _state.value.copy(tunnelConnected = ok)
        ok
    }

    /** 建立反向隧道：本地 Web 端口 → ECS 的 remoteTunnelPort */
    private suspend fun connectTunnel(
        ecsHost: String,
        ecsPort: Int,
        ecsUser: String,
        remoteTunnelPort: Int,
        localWebPort: Int,
        privateKeyPath: String,
        password: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            if (privateKeyPath.isNotBlank()) {
                jsch.addIdentity(privateKeyPath)
            }
            val session: Session = jsch.getSession(ecsUser, ecsHost, ecsPort)
            if (password.isNotBlank()) {
                session.setPassword(password)
            }
            // 非交互：接受 host key（首次连接；生产应校验指纹）
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("ServerAliveInterval", 30)
            session.setConfig("ServerAliveCountMax", 3)
            session.connect(15_000)

            // 反向隧道：ECS 的 remoteTunnelPort → 手机的 localhost:localWebPort
            // 4 参数重载：setPortForwardingR(bind_address, bind_port, host, port)
            // bind_address 留空 = 监听 ECS 所有接口（reasonix 在本机访问 127.0.0.1 也可）
            sshSession = session
            session.setPortForwardingR("", remoteTunnelPort, "127.0.0.1", localWebPort)
            Log.i(TAG, "SSH reverse tunnel established: ECS:$remoteTunnelPort -> local:$localWebPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SSH tunnel failed", e)
            _state.value = _state.value.copy(message = "隧道建立失败: ${e.message}")
            false
        }
    }

    /** 断开隧道 + 停止 Web 服务 */
    fun stop() {
        scope.launch {
            runCatching {
                sshSession?.disconnect()
                sshSession = null
            }.onSuccess {
                _state.value = _state.value.copy(tunnelConnected = false)
            }
            runCatching {
                val intent =
                    android.content.Intent(context, WebServerService::class.java)
                        .setAction(WebServerService.ACTION_STOP)
                context.startService(intent)
            }.onSuccess {
                _state.value = _state.value.copy(webServerRunning = false)
            }
        }
    }

    fun release() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "ReasonixWebBridge"
    }
}
