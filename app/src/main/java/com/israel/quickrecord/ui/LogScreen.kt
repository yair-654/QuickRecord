@file:OptIn(ExperimentalMaterial3Api::class)

package com.israel.quickrecord.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.israel.quickrecord.ErrorLog
import com.israel.quickrecord.R
import com.israel.quickrecord.Times

@Composable
fun LogScreen(onBack: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Problems log") },
                navigationIcon = { IconButton(onClick = onBack) { Ico(R.drawable.ic_back, tint = MaterialTheme.colorScheme.onSurface) } },
                actions = { if (ErrorLog.entries.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("Clear") } },
            )
        }
    ) { pad ->
        if (ErrorLog.entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ico(R.drawable.ic_check, size = 48, tint = OkGreen)
                    Gap()
                    Text("Nothing has gone wrong.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "The last 40 problems and notices. Nothing here leaves the phone.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                items(ErrorLog.entries) { e ->
                    Panel(color = if (e.isProblem) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Ico(if (e.isProblem) R.drawable.ic_warning else R.drawable.ic_info, size = 20,
                                tint = if (e.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            RowGap(12)
                            Column {
                                Text(Times.nice(e.timeMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(e.message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog("Clear the log?", "Removes every entry.", "Clear", onConfirm = { ErrorLog.clear() }, onDismiss = { confirm = false })
    }
}
