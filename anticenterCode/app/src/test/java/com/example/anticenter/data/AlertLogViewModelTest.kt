package com.example.anticenter.data

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.example.anticenter.SelectFeatures
import com.example.anticenter.database.AntiCenterRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlertLogViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    private lateinit var mockRepository: AntiCenterRepository

    private lateinit var viewModel: AlertLogViewModel
    private lateinit var closeable: AutoCloseable

    @Before
    fun setup() {
        closeable = MockitoAnnotations.openMocks(this)
    Dispatchers.setMain(testDispatcher)

        val application = ApplicationProvider.getApplicationContext<Application>()
        viewModel = AlertLogViewModel(application)
        viewModel.repository = mockRepository
    }

    @After
    fun tearDown() {
    Dispatchers.resetMain()
        closeable.close()
    }

    private fun advanceUntilIdle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `loadAlertLogs should populate state on success`() = runTest {
        val logs = listOf(AlertLogItem("time", "type", "source", "status"))
        whenever(mockRepository.getAlertLogItems(SelectFeatures.callProtection)).thenReturn(logs)

        viewModel.loadAlertLogs(SelectFeatures.callProtection)
        advanceUntilIdle()

        assertThat(viewModel.alertLogs.value).isEqualTo(logs)
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.errorMessage.value).isNull()
    }

    @Test
    fun `loadAlertLogs should emit error on exception`() = runTest {
        whenever(mockRepository.getAlertLogItems(SelectFeatures.callProtection))
            .thenThrow(RuntimeException("db error"))

        viewModel.loadAlertLogs(SelectFeatures.callProtection)
        advanceUntilIdle()

        assertThat(viewModel.alertLogs.value).isEmpty()
        assertThat(viewModel.errorMessage.value).contains("Failed to load alert logs")
        assertThat(viewModel.isLoading.value).isFalse()
    }

    @Test
    fun `addAlertLog should refresh list on success`() = runTest {
        val feature = SelectFeatures.callProtection
        val item = AlertLogItem("time", "type", "source", "status")
        whenever(mockRepository.addAlertLogItem(item, feature)).thenReturn(Result.success(1L))
        whenever(mockRepository.getAlertLogItems(feature)).thenReturn(listOf(item))

        viewModel.addAlertLog(item, feature)
        advanceUntilIdle()

        assertThat(viewModel.alertLogs.value).containsExactly(item)
        verify(mockRepository).addAlertLogItem(item, feature)
    }

    @Test
    fun `addAlertLog should report failure`() = runTest {
        val feature = SelectFeatures.callProtection
        val item = AlertLogItem("time", "type", "source", "status")
        whenever(mockRepository.addAlertLogItem(item, feature))
            .thenReturn(Result.failure(RuntimeException("insert failed")))

        viewModel.addAlertLog(item, feature)
        advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).isEqualTo("Failed to add alert log")
    }

    @Test
    fun `initializeSampleData should surface failure`() = runTest {
        whenever(mockRepository.initializeSampleData())
            .thenReturn(Result.failure(RuntimeException("init failed")))

        viewModel.initializeSampleData()
        advanceUntilIdle()

        assertThat(viewModel.errorMessage.value)
            .contains("Failed to initialize sample data")
        assertThat(viewModel.isLoading.value).isFalse()
    }

    @Test
    fun `getAlertLogCount should return callback value`() = runTest {
        whenever(mockRepository.getAlertLogCount(SelectFeatures.emailProtection)).thenReturn(5)
        var callbackResult = -1

        viewModel.getAlertLogCount(SelectFeatures.emailProtection) {
            callbackResult = it
        }
        advanceUntilIdle()

        assertThat(callbackResult).isEqualTo(5)
        assertThat(viewModel.errorMessage.value).isNull()
    }

    @Test
    fun `getAlertLogCount should emit zero on error`() = runTest {
        whenever(mockRepository.getAlertLogCount(SelectFeatures.emailProtection))
            .thenThrow(RuntimeException("count failed"))
        var callbackResult = -1

        viewModel.getAlertLogCount(SelectFeatures.emailProtection) {
            callbackResult = it
        }
        advanceUntilIdle()

        assertThat(callbackResult).isEqualTo(0)
        assertThat(viewModel.errorMessage.value).contains("Error getting count")
    }

    @Test
    fun `cleanupOldLogs should refresh list`() = runTest {
        val feature = SelectFeatures.meetingProtection
        whenever(mockRepository.cleanupOldAlertLogs(feature, 5)).thenReturn(Result.success(2))
        val refreshed = listOf(AlertLogItem("t2", "type", "src", "status"))
        whenever(mockRepository.getAlertLogItems(feature)).thenReturn(refreshed)

        viewModel.cleanupOldLogs(feature, 5)
        advanceUntilIdle()

        assertThat(viewModel.alertLogs.value).isEqualTo(refreshed)
        verify(mockRepository).cleanupOldAlertLogs(feature, 5)
    }

    @Test
    fun `cleanupOldLogs should record error on exception`() = runTest {
        val feature = SelectFeatures.meetingProtection
        whenever(mockRepository.cleanupOldAlertLogs(feature, 5))
            .thenThrow(RuntimeException("cleanup failed"))

        viewModel.cleanupOldLogs(feature, 5)
        advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).contains("Error cleaning up logs")
    }

    @Test
    fun `clearAlertLogsByFeature should clear list on success`() = runTest {
        val feature = SelectFeatures.urlProtection
        whenever(mockRepository.cleanupOldAlertLogs(feature, 0)).thenReturn(Result.success(3))

        viewModel.clearAlertLogsByFeature(feature)
        advanceUntilIdle()

        assertThat(viewModel.alertLogs.value).isEmpty()
        assertThat(viewModel.errorMessage.value).isNull()
        assertThat(viewModel.isLoading.value).isFalse()
    }

    @Test
    fun `clearAlertLogsByFeature should surface failure`() = runTest {
        val feature = SelectFeatures.urlProtection
        whenever(mockRepository.cleanupOldAlertLogs(feature, 0))
            .thenReturn(Result.failure(RuntimeException("failed")))

        viewModel.clearAlertLogsByFeature(feature)
        advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).contains("Failed to clear alert logs")
    }

    @Test
    fun `clearAllData should empty list on success`() = runTest {
        whenever(mockRepository.clearAllData()).thenReturn(Result.success(true))

        viewModel.clearAllData()
        advanceUntilIdle()

        assertThat(viewModel.alertLogs.value).isEmpty()
        assertThat(viewModel.errorMessage.value).isNull()
    }

    @Test
    fun `clearAllData should show failure message`() = runTest {
        whenever(mockRepository.clearAllData())
            .thenReturn(Result.failure(RuntimeException("fail")))

        viewModel.clearAllData()
        advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).contains("Failed to clear all data")
    }

    @Test
    fun `clearError should reset message to null`() = runTest {
        whenever(mockRepository.getAlertLogItems(any<SelectFeatures>()))
            .thenThrow(RuntimeException("boom"))

        viewModel.loadAlertLogs(SelectFeatures.callProtection)
        advanceUntilIdle()
        assertThat(viewModel.errorMessage.value).isNotNull()

        viewModel.clearError()
        assertThat(viewModel.errorMessage.value).isNull()
    }
}
