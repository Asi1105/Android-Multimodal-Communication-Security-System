package com.example.anticenter.data

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.example.anticenter.SelectFeatures
import com.example.anticenter.database.AntiCenterRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
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
import org.robolectric.annotation.Config

/**
 * AllowlistViewModel单元测试
 * 使用Mockito模拟Repository依赖
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33],
    application = Application::class
)
class AllowlistViewModelTest {

    // InstantTaskExecutorRule: 让LiveData/StateFlow在测试中同步执行
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    // 测试用的协程调度器
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModelScope: TestScope

    @Mock
    private lateinit var mockRepository: AntiCenterRepository

    private lateinit var viewModel: TestAllowlistViewModel
    
    // 测试专用的 ViewModel 子类，使用测试协程作用域
    private inner class TestAllowlistViewModel(application: Application, private val testScope: CoroutineScope) : AllowlistViewModel(application) {
        override val coroutineScope: CoroutineScope = testScope
    }

    @Before
    fun setup() {
        // 初始化Mockito
        MockitoAnnotations.openMocks(this)

        // 设置主线程调度器为测试调度器
        Dispatchers.setMain(testDispatcher)

        // 使用 Robolectric 提供的真实 Application 对象
        val application = ApplicationProvider.getApplicationContext<Application>()
        
        // 创建测试专用的 ViewModel，使用测试协程作用域
        viewModelScope = TestScope(testDispatcher + SupervisorJob())
        viewModel = TestAllowlistViewModel(application, viewModelScope)
        
        // 注入 mock repository
        viewModel.repository = mockRepository
    }

    @After
    fun tearDown() {
        // 重置主线程调度器
        Dispatchers.resetMain()
        viewModelScope.cancel()
    }

    // ==================== 初始状态测试 ====================

    @Test
    fun `initial state should have empty list and not loading`() = runTest {
        // Then: 验证初始状态
        assertThat(viewModel.allowlistItems.value).isEmpty()
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.errorMessage.value).isNull()
        assertThat(viewModel.operationSuccess.value).isNull()
    }

    // ==================== 加载测试 ====================

    @Test
    fun `loadAllowlistItems should update items on success`() = runTest {
        // Given: Mock repository返回测试数据
        val testItems = listOf(
            AllowlistItem("id1", "联系人1", "phone", "+138001", "描述1"),
            AllowlistItem("id2", "联系人2", "phone", "+138002", "描述2")
        )
        whenever(mockRepository.getAllowlistItems(any())).doReturn(testItems)

        // When: 调用loadAllowlistItems
        viewModel.loadAllowlistItems(SelectFeatures.callProtection)
        
        // 等待所有协程执行完成
        testScheduler.advanceUntilIdle()

        // Then: 验证状态更新
        assertThat(viewModel.allowlistItems.value).hasSize(2)
        assertThat(viewModel.allowlistItems.value[0].name).isEqualTo("联系人1")
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.errorMessage.value).isNull()
        verify(mockRepository).getAllowlistItems(SelectFeatures.callProtection)
    }

    @Test
    fun `loadAllowlistItems should set error on failure`() = runTest {
        // Given: Mock repository抛出异常
        val exception = RuntimeException("数据库错误")
        whenever(mockRepository.getAllowlistItems(any())).doThrow(exception)

        // When: 调用loadAllowlistItems
        viewModel.loadAllowlistItems(SelectFeatures.callProtection)
        
        // 等待所有协程执行完成
        testScheduler.advanceUntilIdle()

        // Then: 验证错误状态
        assertThat(viewModel.errorMessage.value).isNotNull()
        assertThat(viewModel.errorMessage.value).contains("Failed to load allowlist")
        assertThat(viewModel.allowlistItems.value).isEmpty()
        assertThat(viewModel.isLoading.value).isFalse()
    }

    // ==================== 添加测试 ====================

    @Test
    fun `addAllowlistItem should trigger refresh on success`() = runTest {
        // Given: Mock repository返回成功
        val newItem = AllowlistItem("id3", "新联系人", "phone", "+138003", "描述3")
        whenever(mockRepository.addAllowlistItem(any(), any())).doReturn(Result.success(1L))
        whenever(mockRepository.getAllowlistItems(any())).doReturn(listOf(newItem))

        // When: 添加item
        viewModel.addAllowlistItem(newItem, SelectFeatures.callProtection)
        
        // 等待所有协程执行完成
        testScheduler.advanceUntilIdle()

        // Then: 验证刷新被调用
        verify(mockRepository).addAllowlistItem(newItem, SelectFeatures.callProtection)
        verify(mockRepository).getAllowlistItems(SelectFeatures.callProtection)
        assertThat(viewModel.operationSuccess.value).isEqualTo("Item added successfully")
    }

    @Test
    fun `addAllowlistItem should set error on failure`() = runTest {
        // Given: Mock repository返回失败
        val newItem = AllowlistItem("id3", "新联系人", "phone", "+138003", "描述3")
        val exception = RuntimeException("插入失败")
        whenever(mockRepository.addAllowlistItem(any(), any()))
            .doReturn(Result.failure(exception))

        // When: 添加item
        viewModel.addAllowlistItem(newItem, SelectFeatures.callProtection)
        
        // 等待所有协程执行完成
        testScheduler.advanceUntilIdle()

        // Then: 验证错误信息
        assertThat(viewModel.errorMessage.value).contains("Failed to add item")
    }

    // ==================== 删除测试 ====================

    @Test
    fun `deleteAllowlistItem should trigger refresh on success`() = runTest {
        // Given: Mock repository返回成功
        whenever(mockRepository.deleteAllowlistItem(any())).doReturn(Result.success(true))
        whenever(mockRepository.getAllowlistItems(any())).doReturn(emptyList())

        // When: 删除item
        // viewModel.deleteAllowlistItem("id1", SelectFeatures.callProtection)

        // Then: 验证
        // verify(mockRepository).deleteAllowlistItem("id1")
        // assertThat(viewModel.operationSuccess.value).isEqualTo("Item deleted successfully")
    }

    // ==================== 检查测试 ====================

    @Test
    fun `checkValueInAllowlist should invoke callback with correct result`() = runTest {
        // Given: Mock repository返回true
        whenever(mockRepository.isValueInAllowlist(any(), any())).doReturn(true)
        var callbackResult: Boolean? = null

        // When: 检查value
        viewModel.checkValueInAllowlist("+138001", SelectFeatures.callProtection) { result ->
            callbackResult = result
        }
        
        // 等待所有协程执行完成
        testScheduler.advanceUntilIdle()

        // Then: 回调结果正确
        assertThat(callbackResult).isTrue()
    }

    @Test
    fun `updateAllowlistItem should refresh list on success`() = runTest {
        val feature = SelectFeatures.callProtection
        val item = AllowlistItem("id1", "联系人", "phone", "+123456789", "备注")
        whenever(mockRepository.updateAllowlistItem(item, feature)).doReturn(Result.success(true))
        whenever(mockRepository.getAllowlistItems(feature)).doReturn(listOf(item))

        viewModel.updateAllowlistItem(item, feature)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.allowlistItems.value).containsExactly(item)
        assertThat(viewModel.operationSuccess.value).isEqualTo("Item updated successfully")
        verify(mockRepository).updateAllowlistItem(item, feature)
        verify(mockRepository).getAllowlistItems(feature)
    }

    @Test
    fun `updateAllowlistItem should set error message on failure`() = runTest {
        val feature = SelectFeatures.callProtection
        val item = AllowlistItem("id1", "联系人", "phone", "+123456789", "备注")
        whenever(mockRepository.updateAllowlistItem(item, feature))
            .doReturn(Result.failure(RuntimeException("更新失败")))

        viewModel.updateAllowlistItem(item, feature)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).contains("Failed to update item")
    }

    @Test
    fun `deleteAllowlistItem should refresh list on success`() = runTest {
        val feature = SelectFeatures.callProtection
        whenever(mockRepository.deleteAllowlistItem("id1")).doReturn(Result.success(true))
        whenever(mockRepository.getAllowlistItems(feature)).doReturn(emptyList())

        viewModel.deleteAllowlistItem("id1", feature)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.allowlistItems.value).isEmpty()
        assertThat(viewModel.operationSuccess.value).isEqualTo("Item deleted successfully")
        verify(mockRepository).deleteAllowlistItem("id1")
        verify(mockRepository).getAllowlistItems(feature)
    }

    @Test
    fun `deleteAllowlistItem should set error on failure`() = runTest {
        whenever(mockRepository.deleteAllowlistItem("id1"))
            .doReturn(Result.failure(RuntimeException("删除失败")))

        viewModel.deleteAllowlistItem("id1", SelectFeatures.callProtection)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).contains("Failed to delete item")
    }

    @Test
    fun `saveAllowlistItems should replace items on success`() = runTest {
        val feature = SelectFeatures.callProtection
        val items = listOf(AllowlistItem("id1", "联系人", "phone", "+1", "备注"))
        whenever(mockRepository.saveAllowlistItems(items, feature)).doReturn(Result.success(true))

        viewModel.saveAllowlistItems(items, feature)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.allowlistItems.value).isEqualTo(items)
        assertThat(viewModel.operationSuccess.value).isEqualTo("Allowlist saved successfully")
    }

    @Test
    fun `saveAllowlistItems should set error on failure`() = runTest {
        val feature = SelectFeatures.callProtection
        val items = listOf(AllowlistItem("id1", "联系人", "phone", "+1", "备注"))
        whenever(mockRepository.saveAllowlistItems(items, feature))
            .doReturn(Result.failure(RuntimeException("保存失败")))

        viewModel.saveAllowlistItems(items, feature)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).contains("Failed to save allowlist")
    }

    @Test
    fun `clearAllowlistByFeature should clear list when repository succeeds`() = runTest {
        val feature = SelectFeatures.callProtection
        whenever(mockRepository.saveAllowlistItems(emptyList(), feature)).doReturn(Result.success(true))

        viewModel.clearAllowlistByFeature(feature)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.allowlistItems.value).isEmpty()
        assertThat(viewModel.operationSuccess.value).isEqualTo("Allowlist cleared successfully")
    }

    @Test
    fun `clearAllData should reset list on success`() = runTest {
        whenever(mockRepository.clearAllData()).doReturn(Result.success(true))

        viewModel.clearAllData()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.allowlistItems.value).isEmpty()
        assertThat(viewModel.operationSuccess.value).isEqualTo("All data cleared successfully")
    }

    @Test
    fun `clearAllData should set error on failure`() = runTest {
        whenever(mockRepository.clearAllData())
            .doReturn(Result.failure(RuntimeException("清除失败")))

        viewModel.clearAllData()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.errorMessage.value).contains("Failed to clear all data")
    }

    @Test
    fun `checkValueInAllowlist should handle exceptions as false`() = runTest {
        whenever(mockRepository.isValueInAllowlist(any(), any()))
            .doThrow(RuntimeException("查询失败"))
        var callbackResult: Boolean? = null

        viewModel.checkValueInAllowlist("+138001", SelectFeatures.callProtection) { result ->
            callbackResult = result
        }
        testScheduler.advanceUntilIdle()

        assertThat(callbackResult).isFalse()
        assertThat(viewModel.errorMessage.value).contains("Error checking allowlist")
    }

    @Test
    fun `clearError should reset error message`() = runTest {
        whenever(mockRepository.getAllowlistItems(any())).doThrow(RuntimeException("加载失败"))

        viewModel.loadAllowlistItems(SelectFeatures.callProtection)
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.errorMessage.value).isNotNull()

        viewModel.clearError()
        assertThat(viewModel.errorMessage.value).isNull()
    }

    @Test
    fun `clearSuccess should reset success message`() = runTest {
        whenever(mockRepository.saveAllowlistItems(any<List<AllowlistItem>>(), any()))
            .doReturn(Result.success(true))

        viewModel.saveAllowlistItems(emptyList(), SelectFeatures.callProtection)
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.operationSuccess.value).isNotNull()

        viewModel.clearSuccess()
        assertThat(viewModel.operationSuccess.value).isNull()
    }

    @Test
    fun `generateItemId should return non blank value`() {
        val id = viewModel.generateItemId()
        assertThat(id).isNotEmpty()
    }

    @Test
    fun `validateItem should reject invalid phone when feature is call protection`() {
        val item = AllowlistItem(id = "1", name = "bad", type = "phone", value = "abc", description = "")
        val result = viewModel.validateItem(item, SelectFeatures.callProtection)
        assertThat(result).isInstanceOf(ValidationResult.Error::class.java)
    }

    @Test
    fun `validateItem should reject invalid email`() {
        val item = AllowlistItem(id = "1", name = "bad", type = "email", value = "bad-email", description = "")
        val result = viewModel.validateItem(item, SelectFeatures.emailProtection)
        assertThat(result).isInstanceOf(ValidationResult.Error::class.java)
    }

    @Test
    fun `validateItem should reject invalid url`() {
        val item = AllowlistItem(id = "1", name = "bad", type = "url", value = "example.com", description = "")
        val result = viewModel.validateItem(item, SelectFeatures.urlProtection)
        assertThat(result).isInstanceOf(ValidationResult.Error::class.java)
    }

    @Test
    fun `validateItem should pass for valid data`() {
        val item = AllowlistItem(id = "1", name = "ok", type = "phone", value = "+12345678", description = "")
        val result = viewModel.validateItem(item, SelectFeatures.callProtection)
        assertThat(result).isEqualTo(ValidationResult.Success)
    }
}
