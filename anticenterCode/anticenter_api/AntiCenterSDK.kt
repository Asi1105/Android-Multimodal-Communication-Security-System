package com.example.anticenter.anticenter_api

/**
 * Global accessor / bootstrap object for the AntiCenter API.
 *
 * Usage pattern for feature modules:
 * 1. Call [AntiCenterSDK.getAPI] after app bootstrap (will throw if not initialized).
 * 2. Invoke high‑level persistence operations via the returned [AntiCenterAPI].
 *
 * Initialization (performed once in Application / main entry):
 * ```kotlin
 * val impl = AntiCenterAPIImpl.getInstance(appContext)
 * AntiCenterSDK.setImplementation(impl)
 * ```
 *
 * Rationale:
 * - Centralizes the binding between interface (contract) and concrete implementation.
 * - Avoids leaking the concrete implementation to downstream modules (dependency inversion).
 * - Keeps a simple, framework‑agnostic hand‑rolled DI approach (no external lib dependency).
 */
object AntiCenterSDK {
    private var apiImpl: AntiCenterAPI? = null

    /**
     * Obtain the active [AntiCenterAPI] implementation.
     * @throws IllegalStateException if [setImplementation] has not been invoked yet.
     */
    fun getAPI(): AntiCenterAPI =
        apiImpl ?: throw IllegalStateException("AntiCenterAPI not initialized. Call setImplementation() during app startup.")

    /**
     * Inject the concrete API implementation.
     * Safe to call only once; subsequent calls will overwrite the previous reference (use with care).
     * Consider guarding in caller if re‑binding is undesired.
     */
    fun setImplementation(impl: AntiCenterAPI) {
        apiImpl = impl
    }

    /**
     * @return true if an implementation has been bound.
     */
    fun isInitialized(): Boolean = apiImpl != null
}