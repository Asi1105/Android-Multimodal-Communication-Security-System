/*
 * AlertDialog.kt
 * 
 * AntiCenter Application - Alert Banner Components
 * 
 * This file contains a comprehensive set of banner components for displaying
 * various types of alerts, warnings, and notifications in the AntiCenter app.
 * 
 * Components included:
 * - BaseBanner: Core customizable banner component
 * - FraudWarningBanner: Specialized fraud alert banner
 * - CallRiskBanner: Phone call risk warning banner
 * - SecurityNoticeBanner: Security notification banner
 * - RiskAlertBanner: General risk alert banner
 * - BannerPreviewScreen: Demo screen for testing all banner types
 * 
 * Features:
 * - Animated show/hide transitions
 * - Expandable content support
 * - Customizable colors and styling
 * - Action buttons and close functionality
 * - Material Design 3 theming
 */

package com.example.anticenter.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * Base Banner Component
 * 
 * A customizable banner component with support for titles, messages, icons, action buttons,
 * close functionality, and expandable content.
 * 
 * @param isVisible Whether the banner is visible
 * @param title Title text displayed prominently
 * @param message Main message content
 * @param icon Icon to display alongside the content
 * @param backgroundColor Background color of the banner
 * @param titleColor Color for the title text
 * @param messageColor Color for the message text
 * @param iconColor Color for the icon
 * @param actionText Text for the action button (optional)
 * @param actionButtonColor Color for the action button
 * @param showCloseButton Whether to show the close button
 * @param isExpandable Whether the banner can be expanded to show full message
 * @param onDismiss Callback triggered when banner is dismissed
 * @param onAction Callback triggered when action button is pressed
 */
@Composable
fun BaseBanner(
    isVisible: Boolean,
    title: String,
    message: String,
    icon: ImageVector,
    backgroundColor: Color,
    titleColor: Color,
    messageColor: Color,
    iconColor: Color,
    actionText: String? = null,
    actionButtonColor: Color = MaterialTheme.colorScheme.primary,
    showCloseButton: Boolean = true,
    isExpandable: Boolean = false,
    onDismiss: () -> Unit,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) { -it },
        exit = slideOutVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh
            )
        ) { -it },
        modifier = modifier
            .fillMaxWidth()
            .zIndex(1000f)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            )
        ) {
            Column {
                // 主要内容区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isExpandable) {
                            isExpanded = !isExpanded
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Icon section
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // Text content section
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        if (!isExpanded) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp
                                ),
                                color = messageColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    // Action button area
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Action button
                        actionText?.let { text ->
                            Button(
                                onClick = { onAction?.invoke() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = actionButtonColor,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 4.dp
                                )
                            ) {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                        
                        // Expand/Collapse indicator
                        if (isExpandable) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = iconColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        // Close button
                        if (showCloseButton) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = iconColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                // Expanded detailed content
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(backgroundColor.copy(alpha = 0.7f))
                            .padding(12.dp)
                    ) {
                        Divider(
                            color = iconColor.copy(alpha = 0.3f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                lineHeight = 18.sp
                            ),
                            color = messageColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fraud Warning Banner
 * 
 * A specialized banner component for displaying fraud warnings and alerts.
 * Features expandable content and action buttons for detailed information.
 * 
 * @param isVisible Whether the banner should be displayed
 * @param title Banner title (default: "⚠️ Fraud Risk Warning")
 * @param message Warning message content
 * @param onDismiss Callback when banner is dismissed
 * @param onViewDetails Optional callback for viewing detailed information
 * @param modifier Modifier for customization
 */
@Composable
fun FraudWarningBanner(
    isVisible: Boolean,
    title: String = "⚠️ Fraud Risk Warning",
    message: String,
    onDismiss: () -> Unit,
    onViewDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BaseBanner(
        isVisible = isVisible,
        title = title,
        message = message,
        icon = Icons.Default.Warning,
        backgroundColor = Color(0xFFFFEBEE),
        titleColor = Color(0xFFB71C1C),
        messageColor = Color(0xFF424242),
        iconColor = Color(0xFFD32F2F),
        actionText = "View Details",
        actionButtonColor = Color(0xFFD32F2F),
        showCloseButton = true,
        isExpandable = true,
        onDismiss = onDismiss,
        onAction = onViewDetails,
        modifier = modifier
    )
}

/**
 * Call Risk Banner
 * 
 * A banner component specifically designed for displaying call-related risk warnings.
 * Shows information about potentially fraudulent phone numbers with blocking capability.
 * 
 * @param isVisible Whether the banner should be displayed
 * @param phoneNumber The phone number that triggered the risk alert
 * @param riskLevel Risk assessment level (default: "High Risk")
 * @param onDismiss Callback when banner is dismissed
 * @param onBlock Optional callback for blocking the number
 * @param modifier Modifier for customization
 */
@Composable
fun CallRiskBanner(
    isVisible: Boolean,
    phoneNumber: String,
    riskLevel: String = "High Risk",
    onDismiss: () -> Unit,
    onBlock: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BaseBanner(
        isVisible = isVisible,
        title = "🚨 ${riskLevel} Call",
        message = "Number $phoneNumber has been marked as fraud, recommend hanging up immediately",
        icon = Icons.Default.PriorityHigh,
        backgroundColor = Color(0xFFFFCDD2),
        titleColor = Color(0xFF870000),
        messageColor = Color(0xFF424242),
        iconColor = Color(0xFFB71C1C),
        actionText = "Block",
        actionButtonColor = Color(0xFFB71C1C),
        showCloseButton = true,
        isExpandable = true,
        onDismiss = onDismiss,
        onAction = onBlock,
        modifier = modifier
    )
}

/**
 * Security Notice Banner
 * 
 * A banner component for displaying security-related notifications and alerts.
 * Typically used for account security updates, login alerts, and system notices.
 * 
 * @param isVisible Whether the banner should be displayed
 * @param title Banner title (default: "🔒 Security Notice")
 * @param message Security notice message content
 * @param onDismiss Callback when banner is dismissed
 * @param onAction Optional callback for additional actions
 * @param modifier Modifier for customization
 */
@Composable
fun SecurityNoticeBanner(
    isVisible: Boolean,
    title: String = "🔒 Security Notice",
    message: String,
    onDismiss: () -> Unit,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BaseBanner(
        isVisible = isVisible,
        title = title,
        message = message,
        icon = Icons.Default.Security,
        backgroundColor = Color(0xFFE3F2FD),
        titleColor = Color(0xFF0D47A1),
        messageColor = Color(0xFF424242),
        iconColor = Color(0xFF1976D2),
        actionText = "View Details",
        actionButtonColor = Color(0xFF1976D2),
        showCloseButton = true,
        isExpandable = false,
        onDismiss = onDismiss,
        onAction = onAction,
        modifier = modifier
    )
}

/**
 * Risk Alert Banner
 * 
 * A banner component for displaying general risk alerts and warnings.
 * Features expandable content and continue/proceed functionality.
 * 
 * @param isVisible Whether the banner should be displayed
 * @param title Banner title (default: "⚠️ Risk Alert")
 * @param message Risk alert message content
 * @param onDismiss Callback when banner is dismissed
 * @param onContinue Optional callback for continuing despite the risk
 * @param modifier Modifier for customization
 */
@Composable
fun RiskAlertBanner(
    isVisible: Boolean,
    title: String = "⚠️ Risk Alert",
    message: String,
    onDismiss: () -> Unit,
    onContinue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BaseBanner(
        isVisible = isVisible,
        title = title,
        message = message,
        icon = Icons.Default.Error,
        backgroundColor = Color(0xFFFFF3E0),
        titleColor = Color(0xFFE65100),
        messageColor = Color(0xFF424242),
        iconColor = Color(0xFFFF9800),
        actionText = "Continue",
        actionButtonColor = Color(0xFFFF9800),
        showCloseButton = true,
        isExpandable = true,
        onDismiss = onDismiss,
        onAction = onContinue,
        modifier = modifier
    )
}

// MARK: - Preview Components

/**
 * Banner Preview Screen
 * 
 * A comprehensive preview screen that demonstrates all available banner types
 * and their functionality. Includes interactive buttons to trigger different
 * banner variations and usage instructions.
 */

@Composable
fun BannerPreviewScreen() {
    var showFraudBanner by remember { mutableStateOf(false) }
    var showCallRiskBanner by remember { mutableStateOf(false) }
    var showSecurityBanner by remember { mutableStateOf(false) }
    var showRiskAlertBanner by remember { mutableStateOf(false) }
    var showCustomBanner by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Main content area with control buttons
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Banner Preview",
                style = MaterialTheme.typography.headlineLarge
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Fraud Warning Banner
            Button(
                onClick = { showFraudBanner = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Fraud Warning Banner")
            }
            
            // Call Risk Banner
            Button(
                onClick = { showCallRiskBanner = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB71C1C)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Call Risk Banner")
            }
            
            // Security Notice Banner
            Button(
                onClick = { showSecurityBanner = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1976D2)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Security Notice Banner")
            }
            
            // Risk Alert Banner
            Button(
                onClick = { showRiskAlertBanner = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Risk Alert Banner")
            }
            
            // Custom Banner
            OutlinedButton(
                onClick = { showCustomBanner = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Custom Banner")
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Usage instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Instructions:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Tap buttons above to show different banner types\n" +
                              "• Banners appear at the top of the screen\n" +
                              "• Some banners are expandable (tap to expand)\n" +
                              "• Use close button (×) or action buttons to dismiss",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        // Banner overlay layer - displays at the top of the screen
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            // Fraud Warning Banner
            if (showFraudBanner) {
                FraudWarningBanner(
                    isVisible = showFraudBanner,
                    message = "Suspicious website detected! This site may be attempting to steal your personal information. Please stop all activities immediately.",
                    onDismiss = { showFraudBanner = false },
                    onViewDetails = { showFraudBanner = false }
                )
            }
            
            // Call Risk Banner
            if (showCallRiskBanner) {
                CallRiskBanner(
                    isVisible = showCallRiskBanner,
                    phoneNumber = "138****1234",
                    riskLevel = "Extreme Risk",
                    onDismiss = { showCallRiskBanner = false },
                    onBlock = { showCallRiskBanner = false }
                )
            }
            
            // Security Notice Banner
            if (showSecurityBanner) {
                SecurityNoticeBanner(
                    isVisible = showSecurityBanner,
                    message = "Your account was accessed from a new location. If this wasn't you, please secure your account immediately.",
                    onDismiss = { showSecurityBanner = false },
                    onAction = { showSecurityBanner = false }
                )
            }
            
            // Risk Alert Banner
            if (showRiskAlertBanner) {
                RiskAlertBanner(
                    isVisible = showRiskAlertBanner,
                    message = "This operation involves financial risk. Please verify the recipient information carefully before proceeding.",
                    onDismiss = { showRiskAlertBanner = false },
                    onContinue = { showRiskAlertBanner = false }
                )
            }
            
            // Custom Banner using BaseBanner
            if (showCustomBanner) {
                BaseBanner(
                    isVisible = showCustomBanner,
                    title = "🎉 Custom Banner",
                    message = "This is a custom banner created using the BaseBanner component with purple theme colors.",
                    icon = Icons.Default.Info,
                    backgroundColor = Color(0xFFF3E5F5),
                    titleColor = Color(0xFF4A148C),
                    messageColor = Color(0xFF424242),
                    iconColor = Color(0xFF7B1FA2),
                    actionText = "Got it",
                    actionButtonColor = Color(0xFF7B1FA2),
                    showCloseButton = true,
                    isExpandable = false,
                    onDismiss = { showCustomBanner = false },
                    onAction = { showCustomBanner = false }
                )
            }
        }
    }
}

/**
 * Preview for the Banner Preview Screen
 * 
 * Displays the BannerPreviewScreen in a Material Theme for design review.
 */
@Preview(showBackground = true)
@Composable
fun BannerPreviewScreenPreview() {
    MaterialTheme {
        BannerPreviewScreen()
    }
}

