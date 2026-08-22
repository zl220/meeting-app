package com.meetingapp.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meetingapp.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()

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
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = vm::updateApiKey,
                label = { Text("OpenAI API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.aiWakeName,
                onValueChange = vm::updateAiWakeName,
                label = { Text("AI 唤醒名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("出现在句首时唤醒 AI（如「小谈，你怎么看」）") }
            )
            Button(
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
            if (state.saved) {
                Text("已保存", color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Text(
                "麦克风建议：手机平放桌面中央，屏幕朝上，勿遮挡麦克风。" +
                "一小时录音耗电较快，建议接电源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
