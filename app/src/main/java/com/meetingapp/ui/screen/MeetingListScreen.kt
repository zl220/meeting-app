package com.meetingapp.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.MeetingStatus
import com.meetingapp.viewmodel.MeetingListViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingListScreen(
    onNewMeeting: () -> Unit,
    onOpenActive: (Long) -> Unit,
    onOpenReview: (Long) -> Unit,
    onSettings: () -> Unit,
    vm: MeetingListViewModel = hiltViewModel()
) {
    val meetings by vm.meetings.collectAsState()

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
            FloatingActionButton(onClick = onNewMeeting) {
                Icon(Icons.Default.Add, contentDescription = "新建会议")
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
                            when (meeting.status) {
                                MeetingStatus.RECORDING -> onOpenActive(meeting.id)
                                else -> onOpenReview(meeting.id)
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MeetingRow(meeting: Meeting, onClick: () -> Unit) {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    val dateStr = meeting.startedAt?.let { sdf.format(Date(it)) } ?: "未开始"
    val statusLabel = when (meeting.status) {
        MeetingStatus.RECORDING -> "进行中"
        MeetingStatus.FINISHED -> "已完成"
        MeetingStatus.IDLE -> "准备中"
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(meeting.title) },
        supportingContent = { Text("$dateStr · $statusLabel · ${meeting.estimatedDurationMinutes}min") },
        trailingContent = {
            if (meeting.status == MeetingStatus.RECORDING) {
                Badge { Text("●") }
            }
        }
    )
}
