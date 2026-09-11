package com.meetingapp.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.viewmodel.MinutesReviewViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinutesReviewScreen(
    meetingId: Long,
    onDone: () -> Unit,
    vm: MinutesReviewViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    // Which speaker marker the user is assigning/editing (its occurrence index + current name), or null.
    var assigning by remember { mutableStateOf<SpeakerTarget?>(null) }

    LaunchedEffect(meetingId) { vm.load(meetingId) }

    // Show error as snackbar
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHost.showSnackbar(it); vm.clearError() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("会议纪要") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    // Drive button
                    IconButton(onClick = vm::saveToDrive, enabled = !state.isSaving && state.minutes != null) {
                        if (state.savedToDrive)
                            Icon(Icons.Default.Check, "已存 Drive", tint = MaterialTheme.colorScheme.primary)
                        else
                            Icon(Icons.Default.Save, "存 Google Drive")
                    }
                    // Email button
                    IconButton(onClick = vm::sendEmail, enabled = state.minutes != null) {
                        if (state.emailSent)
                            Icon(Icons.Default.Check, "已发邮件", tint = MaterialTheme.colorScheme.primary)
                        else
                            Icon(Icons.Default.Email, "发邮件给参会者")
                    }
                }
            )
        }
    ) { padding ->

        // Full-screen spinner only when there's nothing to show yet. If a rolling draft is
        // available, we render it right away and finalize in the background (banner below).
        if (state.isGenerating && state.minutes == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("正在生成纪要…")
                }
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Draft shown, finalize still running: tell the user it's the live draft being polished.
            if (state.isGenerating && state.minutes != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "显示的是实时草稿，正在生成最终纪要…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Minutes content — toggle between rendered preview and editable source
            var editing by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("纪要内容", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { editing = !editing }, enabled = state.minutes != null) {
                        Text(if (editing) "预览" else "编辑")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (editing) {
                    OutlinedTextField(
                        value = state.editedContent,
                        onValueChange = vm::updateContent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                        enabled = state.minutes != null,
                        placeholder = { Text("纪要将在此显示…") }
                    )
                } else if (state.editedContent.isBlank()) {
                    Text("纪要将在此显示…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (state.editedContent.contains(UNNAMED_PLACEHOLDER)) {
                        Text(
                            "💡 点击某句话可标注它的发言人",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    MinutesWithPlaceholders(
                        content = state.editedContent,
                        onAssignClick = { target -> assigning = target }
                    )
                }
            }

            // Action buttons row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = vm::saveToDrive,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isSaving && state.minutes != null
                ) {
                    if (state.isSaving) CircularProgressIndicator(Modifier.size(16.dp))
                    else Text(if (state.savedToDrive) "✓ 已存 Drive" else "存 Google Drive")
                }
                OutlinedButton(
                    onClick = vm::sendEmail,
                    modifier = Modifier.weight(1f),
                    enabled = state.minutes != null
                ) {
                    Text(if (state.emailSent) "✓ 已发邮件" else "发邮件")
                }
            }

            // Export the full meeting recording (R7 audio file), if one was saved.
            val audioPath = state.meeting?.audioFilePath
            val audioExists = remember(audioPath) { audioPath != null && File(audioPath).exists() }
            OutlinedButton(
                onClick = { shareRecording(context, audioPath!!, state.meeting?.title) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                enabled = audioExists
            ) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (audioExists) "导出完整录音" else "无录音文件")
            }

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text("完成") }

            Spacer(Modifier.height(16.dp))
        }
    }

    assigning?.let { target ->
        AssignNameDialog(
            participants = state.participants.map { it.name },
            currentName = target.currentName,
            onPick = { name ->
                vm.updateAndPersist(assignSpeakerAt(state.editedContent, target.occurrence, name))
                assigning = null
            },
            onDismiss = { assigning = null }
        )
    }
}

// Speaker markers in minutes text:
//   {{?}}          — unnamed: an attribution phrase (有人提出/有观点认为…) follows it
//   {{name:张三}}  — assigned: renders as "张三：" and stays tappable for correction
private const val UNNAMED_PLACEHOLDER = "{{?}}"
private val SPEAKER_MARKER = Regex("""\{\{\?\}\}|\{\{name:([^}]*)\}\}""")
// Attribution phrases the model prefixes with {{?}} — matched generically so verb variants
// like 有观点认为/指出/提到/建议, 有人提出/认为, 有与会者认为, 会上讨论到 are all covered.
private val ATTRIBUTION_REGEX = Regex("""^\s*(有(人|观点|与会者|参会者|成员)[^，。,、：:\s]{0,4}|会上讨论到)""")
// Leading punctuation/list markers left dangling after a phrase is stripped.
private val LEADING_PUNCT = Regex("""^[\s，。,、：:；;\-*·]+""")

/** A speaker marker the user tapped: its occurrence index (in source order) and current name (null if unnamed). */
private data class SpeakerTarget(val occurrence: Int, val currentName: String?)

/**
 * Render minutes with speaker markers. Each line is scanned for markers; a line with one
 * shows an inline tappable chip (unnamed → "指定发言人"; named → "张三：") plus the rest of
 * the line as text. Marker-free lines render as Markdown. Markers are numbered by global
 * occurrence so a tap maps back to the right marker in the source.
 */
@Composable
private fun MinutesWithPlaceholders(content: String, onAssignClick: (SpeakerTarget) -> Unit) {
    var seen = 0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content.split("\n").forEach { line ->
            val match = SPEAKER_MARKER.find(line)
            if (match == null) {
                if (line.isNotBlank()) {
                    dev.jeziellago.compose.markdowntext.MarkdownText(
                        markdown = line,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            } else {
                val occ = seen
                val assignedName = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                // Rest of the line with the marker removed. For unnamed, also drop the attribution
                // phrase; for both, trim leading list-markers/punctuation.
                val afterMarker = line.removeRange(match.range)
                val rest = (if (assignedName == null) stripLeadingAttribution(afterMarker) else afterMarker)
                    .let { LEADING_PUNCT.replace(it, "") }
                    .trim()
                // Implicit trigger: tapping the sentence opens the speaker picker. No visible button.
                // Named lines prefix a subtly-styled "张三：" (also tappable to correct the name).
                Text(
                    text = if (assignedName == null) rest else "$assignedName：$rest",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAssignClick(SpeakerTarget(occ, assignedName)) }
                        .padding(vertical = 2.dp)
                )
            }
            seen += SPEAKER_MARKER.findAll(line).count()
        }
    }
}

/** Strip a leading attribution phrase (有观点认为/指出…, 有人提出…) after an unnamed marker. */
private fun stripLeadingAttribution(text: String): String {
    val cleaned = LEADING_PUNCT.replace(text, "")
    return ATTRIBUTION_REGEX.replace(cleaned, "")
}

/**
 * Assign/correct the speaker at the Nth (0-based) marker occurrence. Replaces the marker
 * with `{{name:X}}`; if it was unnamed, also drops the attribution phrase and the punctuation
 * right after it so the result reads "张三：应尽快…" instead of "张三：，有观点指出，应尽快…".
 */
private fun assignSpeakerAt(content: String, occurrence: Int, name: String): String {
    val matches = SPEAKER_MARKER.findAll(content).toList()
    val m = matches.getOrNull(occurrence) ?: return content
    val wasUnnamed = m.groupValues.getOrNull(1).isNullOrEmpty()
    var afterEnd = m.range.last + 1
    if (wasUnnamed) {
        // Consume the attribution phrase + surrounding punctuation that follows the marker.
        val tail = content.substring(afterEnd)
        val stripped = ATTRIBUTION_REGEX.replace(LEADING_PUNCT.replace(tail, "")) { "" }
        // Also drop punctuation left between the phrase and the real sentence.
        val cleanedTail = LEADING_PUNCT.replace(stripped, "")
        afterEnd = content.length - cleanedTail.length
    }
    return content.substring(0, m.range.first) + "{{name:$name}}" + content.substring(afterEnd)
}

@Composable
private fun AssignNameDialog(
    participants: List<String>,
    currentName: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customName by remember { mutableStateOf(currentName ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (currentName == null) "这句话是谁说的？" else "修改发言人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (participants.isNotEmpty()) {
                    Text("从参会者中选择", style = MaterialTheme.typography.labelMedium)
                    participants.forEach { name ->
                        OutlinedButton(
                            onClick = { onPick(name) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
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
                onClick = { if (customName.isNotBlank()) onPick(customName) },
                enabled = customName.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Share the meeting recording via the system share sheet using a FileProvider URI. */
private fun shareRecording(context: android.content.Context, path: String, meetingTitle: String?) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "录音文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "会议录音：${meetingTitle ?: "会议"}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出录音").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}

