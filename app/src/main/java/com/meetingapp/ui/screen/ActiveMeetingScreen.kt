package com.meetingapp.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.data.db.entity.Segment
import com.meetingapp.viewmodel.ActiveMeetingViewModel
import com.meetingapp.viewmodel.AiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveMeetingScreen(
    meetingId: Long,
    onFinished: () -> Unit,
    vm: ActiveMeetingViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val segments by vm.segments.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(meetingId) {
        vm.load(meetingId)
        vm.startMeeting()
    }

    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) listState.animateScrollToItem(segments.lastIndex)
    }

    val meeting = state.meeting
    val elapsedMin = state.elapsedMs / 60_000f
    val totalMin = meeting?.estimatedDurationMinutes?.toFloat() ?: 60f
    val progress = (elapsedMin / totalMin).coerceIn(0f, 1f)
    val isOvertime = elapsedMin > totalMin
    val isWarning = progress >= 0.8f && !isOvertime

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meeting?.title ?: "会议进行中") },
                actions = {
                    TimerChip(
                        elapsedMs = state.elapsedMs,
                        isOvertime = isOvertime,
                        isWarning = isWarning
                    )
                    Spacer(Modifier.width(8.dp))
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = when {
                    isOvertime -> MaterialTheme.colorScheme.error
                    isWarning -> Color(0xFFFFA000)
                    else -> MaterialTheme.colorScheme.primary
                }
            )

            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(segments, key = { it.id }) { seg ->
                    SegmentBubble(seg)
                }
            }

            AnimatedVisibility(state.aiState != AiState.IDLE) {
                AiStatusBar(
                    aiState = state.aiState,
                    onCancel = vm::cancelPendingAi
                )
            }

            AiControlPanel(
                onAskAi = { question, role -> vm.askAi(question, role) },
                enabled = state.aiState == AiState.IDLE
            )

            Button(
                onClick = {
                    vm.stopMeeting()
                    onFinished()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("结束会议")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TimerChip(elapsedMs: Long, isOvertime: Boolean, isWarning: Boolean) {
    val totalSeconds = elapsedMs / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    val color = when {
        isOvertime -> MaterialTheme.colorScheme.error
        isWarning -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 16.sp)
}

@Composable
private fun SegmentBubble(seg: Segment) {
    val isAi = seg.isAi
    val speaker = if (isAi) "AI" else (seg.speakerName ?: seg.speakerLabel)
    val bgColor = if (isAi) MaterialTheme.colorScheme.tertiaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .background(bgColor, MaterialTheme.shapes.small)
            .padding(8.dp)
    ) {
        Text(
            speaker,
            style = MaterialTheme.typography.labelSmall,
            color = if (isAi) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        )
        Text(seg.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AiStatusBar(aiState: AiState, onCancel: () -> Unit) {
    val label = when (aiState) {
        AiState.THINKING -> "AI 正在思考…（点取消撤回）"
        AiState.SPEAKING -> "AI 正在发言…（点打断）"
        else -> ""
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, "取消/打断")
            }
        }
    }
}

@Composable
private fun AiControlPanel(
    onAskAi: (String, String?) -> Unit,
    enabled: Boolean
) {
    val roles = listOf("财务" to "财务", "风险" to "风险", "用户" to "用户视角", "法务" to "法律")
    var customQuery by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            roles.forEach { (label, role) ->
                FilterChip(
                    selected = false,
                    onClick = { if (enabled) onAskAi("请从${role}角度发表你的看法", role) },
                    label = { Text(label) },
                    enabled = enabled
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = customQuery,
                onValueChange = { customQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问 AI…") },
                singleLine = true,
                enabled = enabled
            )
            IconButton(
                onClick = {
                    if (customQuery.isNotBlank() && enabled) {
                        onAskAi(customQuery, null)
                        customQuery = ""
                    }
                },
                enabled = enabled && customQuery.isNotBlank()
            ) {
                Icon(
                    if (enabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "发送"
                )
            }
        }
    }
}
