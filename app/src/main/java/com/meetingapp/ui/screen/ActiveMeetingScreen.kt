package com.meetingapp.ui.screen

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.data.db.entity.Segment
import com.meetingapp.viewmodel.ActiveMeetingViewModel
import com.meetingapp.viewmodel.AiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ActiveMeetingScreen(
    meetingId: Long,
    onFinished: () -> Unit,
    vm: ActiveMeetingViewModel = hiltViewModel()
) {
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val state by vm.uiState.collectAsState()
    val segments by vm.segments.collectAsState()
    val listState = rememberLazyListState()

    // Speaker assignment dialog state
    var assigningLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(meetingId) { vm.load(meetingId) }

    LaunchedEffect(micPermission.status.isGranted) {
        if (micPermission.status.isGranted) vm.startMeeting()
        else micPermission.launchPermissionRequest()
    }

    // Auto-scroll to newest segment
    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) listState.animateScrollToItem(segments.lastIndex)
    }

    if (!micPermission.status.isGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("需要麦克风权限才能录音")
                Button(onClick = { micPermission.launchPermissionRequest() }) { Text("授权麦克风") }
            }
        }
        return
    }

    val meeting = state.meeting
    val elapsedSec = state.elapsedMs / 1000
    val totalSec = (meeting?.estimatedDurationMinutes ?: 60) * 60L
    val progress = (state.elapsedMs.toFloat() / (totalSec * 1000)).coerceIn(0f, 1f)
    val isOvertime = state.elapsedMs > totalSec * 1000
    val isWarning = progress >= 0.8f && !isOvertime

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meeting?.title ?: "会议进行中", maxLines = 1) },
                actions = {
                    TimerText(elapsedSec, isOvertime, isWarning)
                    Spacer(Modifier.width(12.dp))
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

            // Live subtitle feed
            if (segments.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "等待转写…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(segments, key = { it.id }) { seg ->
                        SegmentBubble(
                            segment = seg,
                            participants = state.participants,
                            onAssignSpeaker = { label -> assigningLabel = label }
                        )
                    }
                }
            }

            // AI status banner
            AnimatedVisibility(state.aiState != AiState.IDLE) {
                AiStatusBar(state.aiState, onCancel = vm::cancelPendingAi)
            }

            // Error snackbar (transient)
            state.error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            AiControlPanel(
                onAskAi = { q, role -> vm.askAi(q, role) },
                enabled = state.aiState == AiState.IDLE
            )

            Button(
                onClick = { vm.stopMeeting(); onFinished() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("结束会议") }

            Spacer(Modifier.height(8.dp))
        }
    }

    // Speaker assign dialog — triggered by tapping any speaker label in the list
    assigningLabel?.let { label ->
        SpeakerAssignDialog(
            label = label,
            participants = state.participants,
            onAssign = { name ->
                vm.assignSpeakerName(label, name)
                assigningLabel = null
            },
            onDismiss = { assigningLabel = null }
        )
    }
}

@Composable
private fun TimerText(elapsedSec: Long, isOvertime: Boolean, isWarning: Boolean) {
    val h = elapsedSec / 3600
    val m = (elapsedSec % 3600) / 60
    val s = elapsedSec % 60
    val text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    Text(
        text,
        color = when { isOvertime -> MaterialTheme.colorScheme.error; isWarning -> Color(0xFFFFA000); else -> MaterialTheme.colorScheme.onSurface },
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp
    )
}

@Composable
private fun SegmentBubble(
    segment: Segment,
    participants: List<Participant>,
    onAssignSpeaker: (String) -> Unit
) {
    val isAi = segment.isAi
    val displayName = when {
        isAi -> "AI"
        segment.speakerName != null -> segment.speakerName
        else -> segment.speakerLabel
    }
    val isUnnamed = !isAi && segment.speakerName == null
    val bgColor = when {
        isAi -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(bgColor, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Tappable speaker chip
            SpeakerChip(
                name = displayName,
                isUnnamed = isUnnamed,
                isAi = isAi,
                onClick = if (!isAi) ({ onAssignSpeaker(segment.speakerLabel) }) else null
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(segment.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpeakerChip(
    name: String,
    isUnnamed: Boolean,
    isAi: Boolean,
    onClick: (() -> Unit)?
) {
    val containerColor = when {
        isAi -> MaterialTheme.colorScheme.tertiary
        isUnnamed -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        isAi -> MaterialTheme.colorScheme.onTertiary
        isUnnamed -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (isUnnamed) {
                Icon(Icons.Default.Person, null, Modifier.size(12.dp), tint = contentColor)
            }
            Text(
                if (isUnnamed) "$name  ✎" else name,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SpeakerAssignDialog(
    label: String,
    participants: List<Participant>,
    onAssign: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, null) },
        title = { Text("认领发言人：$label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (participants.isNotEmpty()) {
                    Text("从参会者中选择", style = MaterialTheme.typography.labelMedium)
                    participants.forEach { p ->
                        OutlinedButton(
                            onClick = { onAssign(p.name) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(p.name) }
                    }
                    HorizontalDivider()
                    Text("或手动输入", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (customName.isNotBlank()) onAssign(customName) },
                enabled = customName.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AiStatusBar(aiState: AiState, onCancel: () -> Unit) {
    val label = when (aiState) {
        AiState.THINKING -> "AI 思考中…（2.5 秒内可取消）"
        AiState.SPEAKING -> "AI 发言中…（点击打断）"
        else -> ""
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "取消/打断") }
        }
    }
}

@Composable
private fun AiControlPanel(onAskAi: (String, String?) -> Unit, enabled: Boolean) {
    val roles = listOf(
        "财务" to "财务风险",
        "风险" to "潜在风险",
        "用户" to "用户视角",
        "法务" to "法律合规"
    )
    var customQuery by remember { mutableStateOf("") }

    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)) {
        // Quick role chips
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            roles.forEach { (label, role) ->
                FilterChip(
                    selected = false,
                    onClick = { if (enabled) onAskAi("请从${role}角度发表你的看法", role) },
                    label = { Text(label, fontSize = 12.sp) },
                    enabled = enabled
                )
            }
        }
        // Free-text input
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = customQuery,
                onValueChange = { customQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问 AI…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall
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
                    contentDescription = "发送给 AI"
                )
            }
        }
    }
}
