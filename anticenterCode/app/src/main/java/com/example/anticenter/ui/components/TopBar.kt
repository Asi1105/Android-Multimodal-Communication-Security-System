package com.example.anticenter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.example.anticenter.SelectFeatures
import com.example.compose.AppTheme

/**
 * A top app bar with a center-aligned title and a feature selection dropdown menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    menuItems: List<String> = listOf("Call Protection", "Meeting Protection", /*"URL Protection",*/"Email Protection"),
    selectedFeature: SelectFeatures = SelectFeatures.callProtection,
    onMenuItemClick: (String) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    
    // 根据selectedFeature状态计算当前标签
    var currentLabel = when (selectedFeature) {
        SelectFeatures.callProtection -> "Call Protection"
        SelectFeatures.meetingProtection -> "Meeting Protection"
        SelectFeatures.urlProtection -> "URL Protection"
        SelectFeatures.emailProtection -> "Email Protection"
        SelectFeatures.emailBlacklist -> "Email Blacklist"
    }
    CenterAlignedTopAppBar(
        title = { Text(currentLabel) },
        navigationIcon = {
            // Acts as the anchor for the dropdown menu; it must be contained within a Box
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    menuItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                expanded = false
                                onMenuItemClick(item)
                                currentLabel = item

                            }
                        )
                    }
                }
            }
        }
    )
}

@Preview(
    name = "Pixel 7 (Light)",
    device = Devices.PIXEL_7,          // Choose a device to preview
    showBackground = true,             // Show a background in the preview
    showSystemUi = true                // Display system UI such as status and navigation bars
)
@Composable
fun TopBarPreview() {
    AppTheme {
        TopBar()
    }
}
