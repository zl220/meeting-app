package com.meetingapp.ui.screen

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
import com.meetingapp.viewmodel.SpeakerMapping
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

        if (state.isGenerating) {
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

            // Speaker assignment section
            if (state.speakerMappings.isNotEmpty()) {
                SpeakerAssignSection(
                    mappings = state.speakerMappings,
                    participantNames = state.participants.map { it.name },
                    onAssign = { label, name -> vm.assignSpeakerName(label, name) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
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
                    dev.jeziellago.compose.markdowntext.MarkdownText(
                        markdown = state.editedContent,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
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

@Composable
private fun SpeakerAssignSection(
    mappings: List<SpeakerMapping>,
    participantNames: List<String>,
    onAssign: (String, String) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("认领发言人", style = MaterialTheme.typography.titleMedium)
        Text(
            "将录音中识别的「说话人 1/2/3」对应到真实姓名",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        mappings.forEach { mapping ->
            SpeakerRow(
                mapping = mapping,
                participantNames = participantNames,
                onAssign = { name -> onAssign(mapping.label, name) }
            )
        }
    }
}

@Composable
private fun SpeakerRow(
    mapping: SpeakerMapping,
    participantNames: List<String>,
    onAssign: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf(mapping.assignedName ?: "") }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Label badge
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                mapping.label,
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Name input with participant dropdown
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = mapping.assignedName ?: customName,
                onValueChange = { customName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("输入姓名…") },
                trailingIcon = {
                    if (participantNames.isNotEmpty()) {
                        IconButton(onClick = { expanded = true }) {
                            Text("▾", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                participantNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { onAssign(name); customName = name; expanded = false }
                    )
                }
            }
        }

        // Confirm custom name
        if (customName.isNotBlank() && customName != mapping.assignedName) {
            TextButton(onClick = { onAssign(customName) }) { Text("确认") }
        }
    }
}
