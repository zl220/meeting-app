package com.meetingapp.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.viewmodel.MinutesReviewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinutesReviewScreen(
    meetingId: Long,
    onDone: () -> Unit,
    vm: MinutesReviewViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(meetingId) { vm.load(meetingId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会议纪要") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        if (state.isGenerating) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在生成纪要…")
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.speakerLabels.isNotEmpty()) {
                item {
                    Text("认领发言人", style = MaterialTheme.typography.titleMedium)
                }
                items(state.speakerLabels) { label ->
                    SpeakerLabelRow(
                        label = label,
                        participants = state.participants.map { it.name },
                        onAssign = { name -> vm.assignSpeakerName(label, name) }
                    )
                }
                item { HorizontalDivider() }
            }

            item {
                Text("纪要内容（可编辑）", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = state.editedContent,
                    onValueChange = vm::updateContent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    label = { Text("纪要") }
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = vm::saveToDrive,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isSaving
                    ) {
                        Text(if (state.savedToDrive) "已存 Drive" else "存 Drive")
                    }
                    OutlinedButton(
                        onClick = vm::sendEmail,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.emailSent) "已发邮件" else "发邮件")
                    }
                }
            }

            item {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("完成")
                }
                Spacer(Modifier.height(24.dp))
            }

            state.error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SpeakerLabelRow(
    label: String,
    participants: List<String>,
    onAssign: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var assignedName by remember { mutableStateOf<String?>(null) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(assignedName ?: "认领为…")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                participants.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            assignedName = name
                            onAssign(name)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
