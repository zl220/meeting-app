package com.meetingapp.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.MeetingStatus
import com.meetingapp.viewmodel.MeetingListViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MeetingListScreen(
    onNewMeeting: () -> Unit,
    onOpenActive: (Long) -> Unit,
    onOpenReview: (Long) -> Unit,
    onEditMeeting: (Long) -> Unit,
    onSettings: () -> Unit,
    vm: MeetingListViewModel = hiltViewModel()
) {
    val meetings by vm.meetings.collectAsState()
    var pendingDelete by remember { mutableStateOf<Meeting?>(null) }
    var menuFor by remember { mutableStateOf<Meeting?>(null) }
    val hasActiveMeeting = meetings.any { it.status == MeetingStatus.RECORDING }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会议") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!hasActiveMeeting) {
                FloatingActionButton(onClick = onNewMeeting) {
                    Icon(Icons.Default.Add, contentDescription = "新建会议")
                }
            }
        }
    ) { padding ->
        if (meetings.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有会议记录，点 + 新建", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(meetings, key = { it.id }) { meeting ->
                    MeetingRow(
                        meeting = meeting,
                        onClick = {
                            // Only go to Active if the service is actually running (RECORDING + no other active meeting would conflict)
                            // After a crash/force-quit, status may still show RECORDING — treat as Review
                            if (meeting.status == MeetingStatus.RECORDING) onOpenActive(meeting.id)
                            else onOpenReview(meeting.id)
                        },
                        onLongClick = {
                            if (meeting.status != MeetingStatus.RECORDING) {
                                menuFor = meeting
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    menuFor?.let { meeting ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    meeting.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { onEditMeeting(meeting.id); menuFor = null },
                        onLongClick = {}
                    ),
                    leadingContent = { Icon(Icons.Default.Edit, null) },
                    headlineContent = { Text("编辑") }
                )
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { pendingDelete = meeting; menuFor = null },
                        onLongClick = {}
                    ),
                    leadingContent = {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    headlineContent = {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                )
            }
        }
    }

    pendingDelete?.let { meeting ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会议") },
            text = { Text("确定删除「${meeting.title}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteMeeting(meeting); pendingDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetingRow(meeting: Meeting, onClick: () -> Unit, onLongClick: () -> Unit) {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    val dateStr = meeting.startedAt?.let { sdf.format(Date(it)) } ?: "未开始"
    val statusLabel = when (meeting.status) {
        MeetingStatus.RECORDING -> "进行中"
        MeetingStatus.FINISHED -> "已完成"
        MeetingStatus.IDLE -> "准备中"
    }

    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = { Text(meeting.title) },
        supportingContent = {
            Text("$dateStr · $statusLabel · ${meeting.estimatedDurationMinutes}min")
            if (meeting.status != MeetingStatus.RECORDING) {
                Text(
                    "长按：编辑 / 删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        },
        trailingContent = {
            if (meeting.status == MeetingStatus.RECORDING) {
                Badge { Text("●") }
            }
        }
    )
}
