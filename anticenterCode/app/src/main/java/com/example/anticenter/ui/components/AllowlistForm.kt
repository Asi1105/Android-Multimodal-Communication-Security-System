package com.example.anticenter.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.anticenter.data.AllowlistItem
import com.example.anticenter.SelectFeatures

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowlistFormDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<AllowlistItem>) -> Unit,
    initialItems: List<AllowlistItem> = emptyList(),
    featureType: SelectFeatures,
//    isLoading: Boolean = false
) {
    if (isVisible) {
        var allowlistItems by remember(initialItems) { mutableStateOf(initialItems.toMutableList()) }
        var showAddItemDialog by remember { mutableStateOf(false) }

        val collectionTitle = buildCollectionTitle(featureType)

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(allowlistItems)
                        onDismiss()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
            title = {
                Text(
                    text = "Manage $collectionTitle",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    // Add new item button
                    OutlinedButton(
                        onClick = { showAddItemDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add New ${getEntryLabel(featureType)}")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Items list
                    if (allowlistItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No items in allowlist",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(allowlistItems) { index, item ->
                                AllowlistItemCard(
                                    item = item,
                                    onDelete = {
                                        allowlistItems = allowlistItems.toMutableList().apply {
                                            removeAt(index)
                                        }
                                    },
                                    onEdit = { updatedItem ->
                                        allowlistItems = allowlistItems.toMutableList().apply {
                                            set(index, updatedItem)
                                        }
                                    },
                                    featureType = featureType
                                )
                            }
                        }
                    }
                }
            }
        )

        // Add item dialog
        if (showAddItemDialog) {
            AddAllowlistItemDialog(
                onDismiss = { showAddItemDialog = false },
                onAdd = { newItem ->
                    allowlistItems = allowlistItems.toMutableList().apply {
                        add(newItem.copy(id = System.currentTimeMillis().toString()))
                    }
                    showAddItemDialog = false
                },
                featureType = featureType
            )
        }
    }
}

@Composable
fun AllowlistItemCard(
    item: AllowlistItem,
    onDelete: () -> Unit,
    onEdit: (AllowlistItem) -> Unit,
    featureType: SelectFeatures
) {
    var showEditDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { showEditDialog = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.value, // Show the value directly since it's the main identifier
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (item.description.isNotEmpty()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showEditDialog) {
        AddAllowlistItemDialog(
            onDismiss = { showEditDialog = false },
            onAdd = { updatedItem ->
                onEdit(updatedItem.copy(id = item.id))
                showEditDialog = false
            },
            initialItem = item,
            isEditing = true,
            featureType = featureType
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAllowlistItemDialog(
    onDismiss: () -> Unit,
    onAdd: (AllowlistItem) -> Unit,
    initialItem: AllowlistItem = AllowlistItem(),
    isEditing: Boolean = false,
    featureType: SelectFeatures
) {
    val type = getDefaultTypeForFeature(featureType)
    var value by remember { mutableStateOf(initialItem.value) }
    var description by remember { mutableStateOf(initialItem.description) }

    val dialogTitle = buildEntryDialogTitle(featureType, isEditing)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (value.isNotEmpty()) {
                        onAdd(
                            AllowlistItem(
                                name = value, // Use the value as name for simplicity
                                type = type,
                                value = value,
                                description = description
                            )
                        )
                    }
                },
                enabled = value.isNotEmpty()
            ) {
                Text(if (isEditing) "Update" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text(
                text = dialogTitle,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Simplified value input field only
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(getSimplifiedLabel(featureType)) },
                    placeholder = { Text(getSimplifiedPlaceholder(featureType)) },
                    keyboardOptions = when (featureType) {
                        SelectFeatures.callProtection -> KeyboardOptions(keyboardType = KeyboardType.Phone)
                        SelectFeatures.emailProtection, SelectFeatures.emailBlacklist -> KeyboardOptions(keyboardType = KeyboardType.Email)
                        SelectFeatures.urlProtection -> KeyboardOptions(keyboardType = KeyboardType.Uri)
                        SelectFeatures.meetingProtection -> KeyboardOptions.Default
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Optional description field
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    placeholder = { Text("Enter a description") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
            }
        }
    )
}

// Simplified helper functions for feature-specific inputs
private fun getSimplifiedLabel(featureType: SelectFeatures): String {
    return when (featureType) {
        SelectFeatures.callProtection -> "Phone Number"
        SelectFeatures.emailProtection, SelectFeatures.emailBlacklist -> "Email Address"
        SelectFeatures.urlProtection -> "URL"
        SelectFeatures.meetingProtection -> "Meeting URL" // This won't be used but kept for completeness
    }
}

private fun getSimplifiedPlaceholder(featureType: SelectFeatures): String {
    return when (featureType) {
        SelectFeatures.callProtection -> "+1-555-0123"
        SelectFeatures.emailProtection, SelectFeatures.emailBlacklist -> "example@domain.com"
        SelectFeatures.urlProtection -> "https://example.com"
        SelectFeatures.meetingProtection -> "https://meeting.example.com"
    }
}

private fun getValueLabel(type: String): String {
    return when (type) {
        "Phone" -> "Phone Number"
        "Email" -> "Email Address"
        "emailBlacklist" -> "Email Address"
        "URL" -> "URL"
        "IP Address" -> "IP Address"
        else -> "Value"
    }
}

private fun getValuePlaceholder(type: String): String {
    return when (type) {
        "Phone" -> "+1-555-0123"
        "Email" -> "example@domain.com"
        "emailBlacklist" -> "example@domain.com"
        "URL" -> "https://example.com"
        "IP Address" -> "192.168.1.1"
        else -> "Enter value"
    }
}

// Helper functions for feature-specific type filtering
private fun getAvailableTypesForFeature(featureType: SelectFeatures): List<String> {
    return when (featureType) {
        SelectFeatures.callProtection -> listOf("Phone")
        SelectFeatures.emailProtection -> listOf("Email")
    SelectFeatures.emailBlacklist -> listOf("emailBlacklist")
        SelectFeatures.urlProtection -> listOf("URL")
        SelectFeatures.meetingProtection -> listOf("URL") // Meeting URLs
    }
}

private fun getDefaultTypeForFeature(featureType: SelectFeatures): String {
    return getAvailableTypesForFeature(featureType).first()
}

private fun getFeatureDisplayName(featureType: SelectFeatures): String {
    return when (featureType) {
        SelectFeatures.callProtection -> "Call"
        SelectFeatures.emailProtection -> "Email"
        SelectFeatures.emailBlacklist -> "Email"
        SelectFeatures.urlProtection -> "URL"
        SelectFeatures.meetingProtection -> "Meeting"
    }
}

private fun buildCollectionTitle(featureType: SelectFeatures): String {
    return when (featureType) {
        SelectFeatures.emailBlacklist -> "Email Blacklist"
        else -> "${getFeatureDisplayName(featureType)} Allowlist"
    }
}

private fun getEntryLabel(featureType: SelectFeatures): String = getSimplifiedLabel(featureType)

private fun buildEntryDialogTitle(featureType: SelectFeatures, isEditing: Boolean): String {
    val action = if (isEditing) "Edit" else "Add"
    val target = when (featureType) {
        SelectFeatures.emailBlacklist -> "Email Blacklist Entry"
        else -> "${getFeatureDisplayName(featureType)} Entry"
    }
    return "$action $target"
}
