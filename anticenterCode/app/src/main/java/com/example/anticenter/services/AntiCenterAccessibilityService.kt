package com.example.anticenter.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service to monitor app / UI events.
 * TODO: Detect target app launches and suspicious UI patterns.
 */
class AntiCenterAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO process events and forward signals
    }

    override fun onInterrupt() { }
}
