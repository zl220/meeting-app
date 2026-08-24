package com.meetingapp.ui.screen

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

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
    val amplitude by vm.amplitude.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var assigningLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(meetingId) { vm.load(meetingId) }

    LaunchedEffect(micPermission.status.isGranted) {
        if (micPermission.status.isGranted) vm.startMeeting()
        else micPermission.launchPermissionRequest()
    }

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
                    IconButton(onClick = { vm.openMinutesPreview() }) {
                        Icon(Icons.Default.Description, contentDescription = "预览会议纪要")
                    }
                    MeetingTimeDisplay(
                        elapsedMs = state.elapsedMs,
                        totalSec = totalSec,
                        isOvertime = isOvertime,
                        isWarning = isWarning
                    )
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

            // Warn loudly if no API key — transcription, AI and minutes all silently fail otherwise.
            if (state.apiKeyMissing) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "⚠️ 未设置 OpenAI API Key，无法转写、AI 问答和生成纪要。请到「设置」填写后重新开始会议。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Waveform bar — always visible while recording
            WaveformBar(amplitude = amplitude)

            // Live subtitle feed
            if (segments.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MeetingIdleCard(meeting = meeting, participants = state.participants)
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

            AnimatedVisibility(state.aiState != AiState.IDLE) {
                AiStatusBar(state.aiState, onCancel = vm::cancelPendingAi)
            }

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
                enabled = state.aiState == AiState.IDLE,
                onPauseMic = { vm.pauseMicForPtt() },
                onResumeMic = { vm.resumeMicAfterPtt() }
            )

            Button(
                onClick = {
                    scope.launch {
                        vm.stopMeetingAndFinish()
                        onFinished()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("结束会议") }

            Spacer(Modifier.height(8.dp))
        }
    }

    assigningLabel?.let { label ->
        SpeakerAssignDialog(
            label = label,
            participants = state.participants,
            onAssign = { name -> vm.assignSpeakerName(label, name); assigningLabel = null },
            onDismiss = { assigningLabel = null }
        )
    }

    if (state.previewOpen) {
        MinutesPreviewDialog(
            content = state.previewContent,
            onDismiss = { vm.closeMinutesPreview() }
        )
    }
}

// ── Minutes preview dialog ──────────────────────────────────────────────────

@Composable
private fun MinutesPreviewDialog(content: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Description, null) },
        title = { Text("当前会议纪要") },
        text = {
            if (content == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在整理最新纪要…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    dev.jeziellago.compose.markdowntext.MarkdownText(
                        markdown = content,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ── Waveform animation ──────────────────────────────────────────────────────

@Composable
private fun WaveformBar(amplitude: Float) {
    // Smooth the raw amplitude so the wave doesn't snap
    val smoothAmp = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        smoothAmp.animateTo(amplitude, animationSpec = tween(80, easing = LinearEasing))
    }

    // Continuously scrolling phase — the wave always moves even at rest
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI.toFloat()),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase"
    )

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp)
    ) {
        val cx = size.height / 2f
        val amp = smoothAmp.value
        // At rest: tiny ripple. Speaking: full height
        val waveAmp = if (amp < 0.015f) cx * 0.06f else (cx * 0.85f * amp).coerceIn(cx * 0.08f, cx * 0.92f)
        val color = if (amp < 0.015f) idleColor else activeColor
        val strokeW = if (amp < 0.015f) 2f else 3.5f

        // Draw a single smooth sine curve across the full width
        val path = Path()
        val steps = 200
        for (s in 0..steps) {
            val x = size.width * s / steps
            val angle = phase + (x / size.width) * 2f * Math.PI.toFloat() * 2.5f  // ~2.5 cycles
            val y = cx + waveAmp * sin(angle)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = strokeW, cap = StrokeCap.Round))
    }
}

// ── Timer ───────────────────────────────────────────────────────────────────

/** Formats a second count as H:MM:SS (or MM:SS when under an hour). */
private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Top-bar time display: "已用 / 总时长" on top, wall clock below.
 * The clock re-derives from the system time on each elapsedMs tick (updates ~1/s).
 */
@Composable
private fun MeetingTimeDisplay(elapsedMs: Long, totalSec: Long, isOvertime: Boolean, isWarning: Boolean) {
    val elapsedSec = elapsedMs / 1000
    val elapsedColor = when {
        isOvertime -> MaterialTheme.colorScheme.error
        isWarning -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.onSurface
    }
    // Recomputed whenever elapsedMs changes (the VM ticks it every second).
    val clock = remember(elapsedSec) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDuration(elapsedSec),
                color = elapsedColor,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Text(
                " / ${formatDuration(totalSec)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Text(
            "⏰ $clock",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

// ── Idle card ───────────────────────────────────────────────────────────────

@Composable
private fun MeetingIdleCard(
    meeting: com.meetingapp.data.db.entity.Meeting?,
    participants: List<Participant>
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!meeting?.agenda.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("议程", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(meeting!!.agenda!!, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
        if (participants.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("参会者", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    participants.forEach { p ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(p.name, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
            HorizontalDivider()
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "正在录音，字幕约 8 秒后出现",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

// ── Segment bubble ──────────────────────────────────────────────────────────

@Composable
private fun SegmentBubble(segment: Segment, participants: List<Participant>, onAssignSpeaker: (String) -> Unit) {
    val isAi = segment.isAi
    val displayName = when {
        isAi -> "AI"
        segment.speakerName != null -> segment.speakerName
        else -> segment.speakerLabel
    }
    val isUnnamed = !isAi && segment.speakerName == null
    val bgColor = if (isAi) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier.fillMaxWidth().background(bgColor, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        SpeakerChip(name = displayName, isUnnamed = isUnnamed, isAi = isAi,
            onClick = if (!isAi) ({ onAssignSpeaker(segment.speakerLabel) }) else null)
        Spacer(Modifier.height(2.dp))
        Text(segment.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpeakerChip(name: String, isUnnamed: Boolean, isAi: Boolean, onClick: (() -> Unit)?) {
    val containerColor = when { isAi -> MaterialTheme.colorScheme.tertiary; isUnnamed -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.primaryContainer }
    val contentColor = when { isAi -> MaterialTheme.colorScheme.onTertiary; isUnnamed -> MaterialTheme.colorScheme.onSecondaryContainer; else -> MaterialTheme.colorScheme.onPrimaryContainer }
    Surface(color = containerColor, shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (isUnnamed) Icon(Icons.Default.Person, null, Modifier.size(12.dp), tint = contentColor)
            Text(if (isUnnamed) "$name  ✎" else name, style = MaterialTheme.typography.labelSmall, color = contentColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Speaker assign dialog ───────────────────────────────────────────────────

@Composable
private fun SpeakerAssignDialog(label: String, participants: List<Participant>, onAssign: (String) -> Unit, onDismiss: () -> Unit) {
    var customName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, null) },
        title = { Text("标注发言人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前无法自动区分说话人，指定的姓名会应用到所有「发言」片段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (participants.isNotEmpty()) {
                    Text("从参会者中选择", style = MaterialTheme.typography.labelMedium)
                    participants.forEach { p -> OutlinedButton(onClick = { onAssign(p.name) }, modifier = Modifier.fillMaxWidth()) { Text(p.name) } }
                    HorizontalDivider()
                    Text("或手动输入", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (customName.isNotBlank()) onAssign(customName) }, enabled = customName.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ── AI status bar ───────────────────────────────────────────────────────────

@Composable
private fun AiStatusBar(aiState: AiState, onCancel: () -> Unit) {
    val label = when (aiState) {
        AiState.THINKING -> "AI 思考中…（2.5 秒内可取消）"
        AiState.SPEAKING -> "AI 发言中…（点击打断）"
        else -> ""
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "取消/打断") }
        }
    }
}

// ── AI control panel ────────────────────────────────────────────────────────

@Composable
private fun AiControlPanel(
    onAskAi: (String, String?) -> Unit,
    enabled: Boolean,
    onPauseMic: () -> Unit,
    onResumeMic: () -> Unit
) {
    val context = LocalContext.current
    var pttState by remember { mutableStateOf(PttState.IDLE) }
    var recognizedText by remember { mutableStateOf("") }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)) {
        val pttColor = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            pttState == PttState.LISTENING -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
        val pttContentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            pttState == PttState.LISTENING -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }

        Surface(
            color = pttColor,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(enabled, recognizer) {
                    detectTapGestures(
                        onPress = {
                            if (!enabled || recognizer == null) return@detectTapGestures

                            // Release the microphone so SpeechRecognizer can use it
                            onPauseMic()
                            pttState = PttState.LISTENING
                            recognizedText = ""

                            // Deferred that completes when recognition finishes (success or error)
                            val resultDeferred = CompletableDeferred<String>()

                            recognizer.setRecognitionListener(object : RecognitionListener {
                                override fun onResults(results: Bundle) {
                                    val t = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                        ?.firstOrNull() ?: ""
                                    recognizedText = t
                                    pttState = PttState.RECOGNIZED
                                    resultDeferred.complete(t)
                                }
                                override fun onPartialResults(partial: Bundle) {
                                    recognizedText = partial
                                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                        ?.firstOrNull() ?: ""
                                }
                                override fun onError(error: Int) {
                                    pttState = PttState.IDLE
                                    resultDeferred.complete("")
                                }
                                override fun onReadyForSpeech(p: Bundle?) {}
                                override fun onBeginningOfSpeech() {}
                                override fun onRmsChanged(v: Float) {}
                                override fun onBufferReceived(b: ByteArray?) {}
                                override fun onEndOfSpeech() {}
                                override fun onEvent(t: Int, p: Bundle?) {}
                            })
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            }
                            recognizer.startListening(intent)

                            // Wait for finger up, then tell recognizer to stop recording
                            // but keep waiting for the final result (up to 6s)
                            tryAwaitRelease()
                            recognizer.stopListening()
                            pttState = PttState.RECOGNIZED  // show "识别中…"

                            val finalText = withTimeoutOrNull(6_000) { resultDeferred.await() } ?: recognizedText
                            if (finalText.isNotBlank()) onAskAi(finalText, null)

                            recognizedText = ""
                            pttState = PttState.IDLE
                            kotlinx.coroutines.delay(300)
                            onResumeMic()
                        }
                    )
                }
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(
                    if (pttState == PttState.LISTENING) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    tint = pttContentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        when (pttState) {
                            PttState.LISTENING -> "正在听…松开即可"
                            PttState.RECOGNIZED -> "识别中，稍候发送"
                            PttState.IDLE -> "按住，请 AI 发言"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = pttContentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (recognizedText.isNotBlank()) {
                        Text(recognizedText, style = MaterialTheme.typography.bodySmall, color = pttContentColor, maxLines = 1)
                    }
                }
            }
        }
    }
}

private enum class PttState { IDLE, LISTENING, RECOGNIZED }
