package com.meetingapp.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.BuildConfig
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val participants by vm.participants.collectAsState()
    var showAddParticipant by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = vm::updateApiKey,
                label = { Text("OpenAI API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.aiWakeName,
                onValueChange = vm::updateAiWakeName,
                label = { Text("AI 唤醒名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("出现在句首时唤醒 AI（如「小谈，你怎么看」）") }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
            if (state.saved) {
                Spacer(Modifier.height(8.dp))
                Text("已保存", color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Participants / contacts — global list reused across meetings.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("参会人", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showAddParticipant = true }) {
                    Icon(Icons.Default.Add, "新增参会人")
                }
            }
            Text(
                "常用联系人列表，开会时可直接从中勾选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (participants.isEmpty()) {
                Text(
                    "还没有参会人，点右上角 + 添加。",
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            } else {
                participants.forEach { p ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Person, null) },
                        headlineContent = { Text(p.name) },
                        supportingContent = { if (p.email.isNotBlank()) Text(p.email) },
                        trailingContent = {
                            IconButton(onClick = { vm.deleteParticipant(p) }) {
                                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Text(
                "麦克风建议：手机平放桌面中央，屏幕朝上，勿遮挡麦克风。" +
                "一小时录音耗电较快，建议接电源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAddParticipant) {
        AddParticipantDialog(
            onDismiss = { showAddParticipant = false },
            onSave = { name, email ->
                vm.addParticipant(name, email)
                showAddParticipant = false
            }
        )
    }
}

@Composable
private fun AddParticipantDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增参会人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名 *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, email) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
