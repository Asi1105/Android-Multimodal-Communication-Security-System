package com.example.anticenter.database

import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AllowlistItem
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.data.ContentLogItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AntiCenterRepositoryTest {

    @Mock
    private lateinit var mockDatabaseManager: DatabaseManager

    private lateinit var repository: AntiCenterRepository
    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        val constructor = AntiCenterRepository::class.java.getDeclaredConstructor(DatabaseManager::class.java)
        constructor.isAccessible = true
        repository = constructor.newInstance(mockDatabaseManager)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun `addAllowlistItem returns success when insert succeeds`() = runBlocking {
        val item = AllowlistItem("1", "Mom", "Phone", "+1", "Family")
        whenever(mockDatabaseManager.insertAllowlistItem(item, SelectFeatures.callProtection)).doReturn(5L)

        val result = repository.addAllowlistItem(item, SelectFeatures.callProtection)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(5L)
    }

    @Test
    fun `addAllowlistItem returns failure when insert fails`() = runBlocking {
        val item = AllowlistItem("1", "Mom", "Phone", "+1", "Family")
        whenever(mockDatabaseManager.insertAllowlistItem(item, SelectFeatures.callProtection)).doReturn(-1L)

        val result = repository.addAllowlistItem(item, SelectFeatures.callProtection)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `updateAllowlistItem returns failure when no rows updated`() = runBlocking {
        val item = AllowlistItem("1", "Mom", "Phone", "+1", "Family")
        whenever(mockDatabaseManager.updateAllowlistItem(item, SelectFeatures.callProtection)).doReturn(0)

        val result = repository.updateAllowlistItem(item, SelectFeatures.callProtection)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `deleteAllowlistItem returns success`() = runBlocking {
        whenever(mockDatabaseManager.deleteAllowlistItem("1")).doReturn(1)

        val result = repository.deleteAllowlistItem("1")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `saveAllowlistItems replaces all entries`() = runBlocking {
        val feature = SelectFeatures.emailProtection
        val items = listOf(
            AllowlistItem("1", "Sender1", "Email", "user1@example.com", "Friend"),
            AllowlistItem("2", "Sender2", "Email", "user2@example.com", "Work")
        )
        whenever(mockDatabaseManager.deleteAllowlistItemsByFeature(feature)).doReturn(2)
        whenever(mockDatabaseManager.insertAllowlistItem(any(), any())).doReturn(1L)

        val result = repository.saveAllowlistItems(items, feature)

        assertThat(result.isSuccess).isTrue()
        verify(mockDatabaseManager).deleteAllowlistItemsByFeature(feature)
        items.forEach { verify(mockDatabaseManager).insertAllowlistItem(it, feature) }
    }

    @Test
    fun `getAlertLogItems delegates to database manager`() = runBlocking {
        val feature = SelectFeatures.callProtection
        val logs = listOf(AlertLogItem("time", "type", "source", "status"))
        whenever(mockDatabaseManager.getAlertLogItems(feature)).doReturn(logs)

        val result = repository.getAlertLogItems(feature)

        assertThat(result).isEqualTo(logs)
    }

    @Test
    fun `cleanupOldAlertLogs returns deleted count`() = runBlocking {
        val feature = SelectFeatures.meetingProtection
        whenever(mockDatabaseManager.cleanupOldAlertLogs(feature, 10)).doReturn(3)

        val result = repository.cleanupOldAlertLogs(feature, 10)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(3)
    }

    @Test
    fun `addContentLogItem returns failure when insert fails`() = runBlocking {
        val item = ContentLogItem(id = 0, type = "email", timestamp = 0L, content = "data")
        whenever(mockDatabaseManager.insertContentLogItem(item)).doReturn(-1L)

        val result = repository.addContentLogItem(item)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `deleteContentLogItemsByType returns boolean result`() = runBlocking {
        whenever(mockDatabaseManager.deleteContentLogItemsByType("email")).doReturn(0)

        val result = repository.deleteContentLogItemsByType("email")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }

    @Test
    fun `clearAllData returns failure when database throws`() = runBlocking {
        doThrow(RuntimeException("db failure")).whenever(mockDatabaseManager).clearAllData()

        val result = repository.clearAllData()

        assertThat(result.isFailure).isTrue()
    }
}
