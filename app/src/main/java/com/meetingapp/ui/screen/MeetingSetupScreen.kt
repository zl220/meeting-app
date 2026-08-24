package com.meetingapp.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.viewmodel.MeetingSetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingSetupScreen(
    onCreated: (Long) -> Unit,
    onBack: () -> Unit,
    vm: MeetingSetupViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val participants by vm.allParticipants.collectAsState()
    var showAddParticipant by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.savedMeetingId) {
        state.savedMeetingId?.let { onCreated(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建会议") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding(),        // content scrolls above keyboard automatically
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = vm::updateTitle,
                    label = { Text("会议标题 *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
            }
            item {
                OutlinedTextField(
                    value = state.agenda,
                    onValueChange = vm::updateAgenda,
                    label = { Text("议程（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
            }
            item {
                OutlinedTextField(
                    value = state.location,
                    onValueChange = vm::updateLocation,
                    label = { Text("地点（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() })
                )
            }
            item {
                DurationPicker(
                    durationMinutes = state.durationMinutes,
                    onChanged = vm::updateDuration
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("参会者", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showAddParticipant = true }) {
                        Icon(Icons.Default.Add, "添加参会者")
                    }
                }
            }
            items(participants, key = { it.id }) { p ->
                ParticipantRow(
                    participant = p,
                    selected = p.id in state.selectedParticipantIds,
                    onToggle = { vm.toggleParticipant(p.id) },
                    onDelete = { vm.deleteParticipant(p) }
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        keyboardController?.hide()
                        vm.createMeeting()
                    },
                    enabled = state.title.isNotBlank() && !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp))
                    else Text("开始会议")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddParticipant) {
        AddParticipantDialog(
            onDismiss = { showAddParticipant = false },
            onSave = { p ->
                vm.saveParticipant(p)
                showAddParticipant = false
            }
        )
    }
}

@Composable
private fun DurationPicker(durationMinutes: Int, onChanged: (Int) -> Unit) {
    val options = listOf(30, 45, 60, 90, 120, 180)
    var showCustom by remember { mutableStateOf(durationMinutes !in options) }
    var customText by remember { mutableStateOf(if (durationMinutes !in options) durationMinutes.toString() else "") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("预计时长", style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            options.forEach { mins ->
                FilterChip(
                    selected = !showCustom && durationMinutes == mins,
                    onClick = { showCustom = false; customText = ""; onChanged(mins) },
                    label = { Text("${mins}min") }
                )
            }
            FilterChip(
                selected = showCustom,
                onClick = { showCustom = true },
                label = { Text("自定义") }
            )
        }
        if (showCustom) {
            OutlinedTextField(
                value = customText,
                onValueChange = { v ->
                    customText = v.filter { it.isDigit() }.take(3)
                    val mins = customText.toIntOrNull()
                    if (mins != null && mins in 1..600) onChanged(mins)
                },
                label = { Text("自定义时长（分钟）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { /* close keyboard */ }),
                supportingText = { Text("1–600 分钟") }
            )
        }
    }
}

@Composable
private fun ParticipantRow(
    participant: Participant,
    selected: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(participant.name) },
        supportingContent = { Text(participant.email) },
        leadingContent = {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun AddParticipantDialog(onDismiss: () -> Unit, onSave: (Participant) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加参会者") },
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
                    label = { Text("邮箱") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(Participant(name = name, email = email)) },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
