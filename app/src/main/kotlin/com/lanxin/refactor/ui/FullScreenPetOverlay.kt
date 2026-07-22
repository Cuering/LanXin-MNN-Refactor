package com.lanxin.refactor.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lanxin.voice.pet.Live2DWebViewHost

/**
 * 全屏宠物模式：Live2D 铺满 + 底部精简控制条。
 * 复用 [petHost] 已有 WebView（从 companion 挂过来）。
 */
@Composable
fun FullScreenPetOverlay(
    petHost: Live2DWebViewHost,
    statusLine: String,
    micStatus: String,
    recording: Boolean,
    vadEnabled: Boolean,
    onToggleVad: () -> Unit,
    onMicClick: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        color = Color.Black
    ) {
        Box(Modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).also { container ->
                        container.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        container.setBackgroundColor(android.graphics.Color.BLACK)
                        petHost.attach(ctx, container)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "全屏宠物",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = statusLine,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = micStatus,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onToggleVad) {
                    Text(
                        if (vadEnabled) "VAD开" else "VAD关",
                        color = Color.White
                    )
                }
                Button(
                    onClick = onMicClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) {
                            Color(0xFFB71C1C)
                        } else {
                            Color(0xFF1565C0)
                        }
                    )
                ) {
                    Text(
                        when {
                            recording && vadEnabled -> "录音中·听静音…"
                            recording -> "停麦发送"
                            else -> "开始录音"
                        }
                    )
                }
                Button(onClick = onExit) {
                    Text("退出全屏")
                }
            }
        }
    }
}
