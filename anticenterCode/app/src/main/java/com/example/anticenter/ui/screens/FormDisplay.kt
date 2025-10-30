package com.example.anticenter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AlertLogViewModel
import com.example.anticenter.data.AlertLogItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDisplay(
    selectFeatures: SelectFeatures,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit = {},
    viewModel: AlertLogViewModel = viewModel()
) {
    // Get state from ViewModel
    val alertLogs by viewModel.alertLogs
    val isLoading by viewModel.isLoading
    val errorMessage by viewModel.errorMessage
    
    // Load data when feature type changes
    LaunchedEffect(selectFeatures) {
        viewModel.loadAlertLogs(selectFeatures)
    }
    
    // Display error message using Snackbar
    errorMessage?.let { message ->
        LaunchedEffect(message) {
            // This could show Snackbar or other error notifications
            // Currently using println, actual projects would use SnackbarHost
            println("Error: $message")
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Show loading indicator if loading
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        
        // Display error state
        errorMessage?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { 
                        viewModel.clearError()
                        viewModel.refreshData(selectFeatures)
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        
        // Table content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Table header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Time",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(2.5f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "Type",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(2f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "Source",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(2f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "Status",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1.5f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            
            // Show empty state if no data and not loading
            if (alertLogs.isEmpty() && !isLoading && errorMessage == null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No alert logs found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Table rows
            items(alertLogs) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = log.time,
                            modifier = Modifier.weight(2.5f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = log.type,
                            modifier = Modifier.weight(2f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = log.source,
                            modifier = Modifier.weight(2f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        Box(
                            modifier = Modifier.weight(1.5f),
                            contentAlignment = Alignment.Center
                        ) {
                            Badge(
                                containerColor = when (log.status) {
                                    "Detected" -> MaterialTheme.colorScheme.error
                                    "Suspicious" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.secondary
                                }
                            ) {
                                Text(
                                    text = log.status,
                                    color = when (log.status) {
                                        "Detected" -> MaterialTheme.colorScheme.onError
                                        "Suspicious" -> MaterialTheme.colorScheme.onTertiary
                                        else -> MaterialTheme.colorScheme.onSecondary
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getFeatureDisplayName(feature: SelectFeatures): String {
    return when (feature) {
        SelectFeatures.callProtection -> "Call Protection"
        SelectFeatures.meetingProtection -> "Meeting Protection"
        SelectFeatures.urlProtection -> "URL Protection"
        SelectFeatures.emailProtection -> "Email Protection"
        SelectFeatures.emailBlacklist -> "Email Blacklist"
    }
}