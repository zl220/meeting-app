package com.meetingapp.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.viewmodel.PendingVoiceRow
import com.meetingapp.viewmodel.VoiceLibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLibraryScreen(
    onBack: () -> Unit,
    vm: VoiceLibraryViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val participants by vm.participants.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var annotating by remember { mutableStateOf<PendingVoiceRow?>(null) }
    var deletingSampleId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("声纹库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    "已保存 ${state.namedCount} 个已命名声纹（${state.named.size} 人）、" +
                        "${state.pendingCount} 个待标注声纹。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            if (state.named.isNotEmpty()) {
                item { SectionHeader("已命名") }
                state.named.forEach { person ->
                    item(key = "named-head-${person.participantId}") {
                        Text(
                            person.participantName,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(person.samples, key = { "vs-${it.id}" }) { sample ->
                        SampleRow(
                            durationMs = sample.durationMs,
                            qualityScore = sample.qualityScore,
                            isPlaying = state.playingPath == sample.filePath,
                            onPlay = { vm.togglePlay(sample.filePath) },
                            onDelete = { deletingSampleId = sample.id }
                        )
                    }
                }
            }

            if (state.pending.isNotEmpty()) {
                item { SectionHeader("待标注") }
                state.pending.forEach { row ->
                    item(key = "pending-head-${row.meetingId}-${row.speakerLabel}") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${row.speakerLabel}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    row.meetingTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            TextButton(onClick = { annotating = row }) {
                                Icon(Icons.Default.Label, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("标注")
                            }
                        }
                    }
                    items(row.clips, key = { "pv-${it.id}" }) { clip ->
                        SampleRow(
                            durationMs = clip.durationMs,
                            qualityScore = clip.qualityScore,
                            isPlaying = state.playingPath == clip.filePath,
                            onPlay = { vm.togglePlay(clip.filePath) },
                            onDelete = { vm.deletePendingSample(clip.id) }
                        )
                    }
                }
            }

            if (state.named.isEmpty() && state.pending.isEmpty()) {
                item {
                    Text(
                        "还没有保存任何声纹。开会启用说话人识别后，声纹会自动积累到这里。",
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    annotating?.let { row ->
        AnnotateDialog(
            row = row,
            participantNames = participants.map { it.name },
            onDismiss = { annotating = null },
            onConfirm = { name -> vm.annotatePending(row, name); annotating = null }
        )
    }

    deletingSampleId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingSampleId = null },
            title = { Text("删除声纹") },
            text = { Text("确定删除这个声纹样本？删除后该样本不再用于说话人识别。") },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteNamedSample(id); deletingSampleId = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletingSampleId = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SampleRow(
    durationMs: Long,
    qualityScore: Double,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text("时长 ${durationMs / 1000.0}s") },
        supportingContent = { Text("质量 ${(qualityScore * 100).toInt()}%") },
        leadingContent = {
            IconButton(onClick = onPlay) {
                if (isPlaying) Icon(Icons.Default.Stop, "停止")
                else Icon(Icons.Default.PlayArrow, "播放")
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnnotateDialog(
    row: PendingVoiceRow,
    participantNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标注「${row.speakerLabel}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "播放上面的声音确认是谁说的，然后填写或选择姓名。该说话人的所有声纹会归到此人名下。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名 *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
                if (participantNames.isNotEmpty()) {
                    Text("已有联系人：", style = MaterialTheme.typography.labelSmall)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        participantNames.forEach { n ->
                            SuggestionChip(onClick = { name = n }, label = { Text(n) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
