package com.example.anticenter.database

import android.content.Context
import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AllowlistItem
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.data.ContentLogItem
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DatabaseManager单元测试
 * 使用Robolectric在JVM上运行Android组件测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE) // 使用Android API 28, 不需要manifest
class DatabaseManagerTest {

    private lateinit var databaseManager: DatabaseManager
    private lateinit var context: Context

    @Before
    fun setup() {
        // 获取测试用的Application Context (Robolectric 4.x 使用方式)
        context = RuntimeEnvironment.getApplication()
        context = RuntimeEnvironment.getApplication()
        
        // 每次测试前清空数据库实例
        clearDatabaseInstance()
        
        // 创建DatabaseManager实例
        databaseManager = DatabaseManager.getInstance(context)
    }

    @After
    fun tearDown() {
        // 测试后关闭数据库连接
        databaseManager.close()
        clearDatabaseInstance()
        
        // 删除测试数据库文件
        context.deleteDatabase("anticenter.db")
    }

    // 使用反射清空单例实例
    private fun clearDatabaseInstance() {
        try {
            val instanceField = DatabaseManager::class.java.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 基础测试 ====================

    @Test
    fun `test database creation`() {
        // Given: DatabaseManager已初始化
        
        // When: 获取可读数据库
        val db = databaseManager.readableDatabase
        
        // Then: 数据库不为null且已打开
        assertThat(db).isNotNull()
        assertThat(db.isOpen).isTrue()
    }

    @Test
    fun `test singleton pattern`() {
        // Given & When: 多次获取实例
        val instance1 = DatabaseManager.getInstance(context)
        val instance2 = DatabaseManager.getInstance(context)
        
        // Then: 返回同一实例
        assertThat(instance1).isSameInstanceAs(instance2)
    }

    // ==================== Allowlist CRUD测试 ====================

    @Test
    fun `test insert allowlist item successfully`() {
        // Given: 创建一个allowlist item
        val item = AllowlistItem(
            id = "test_id_001",
            name = "测试联系人",
            type = "phone",
            value = "+8613800138000",
            description = "测试描述"
        )
        
        // When: 插入数据
        val result = databaseManager.insertAllowlistItem(item, SelectFeatures.callProtection)
        
        // Then: 插入成功(返回值>0)
        assertThat(result).isGreaterThan(0)
    }

    @Test
    fun `test get allowlist items by feature type`() {
        // Given: 插入多个不同feature的items
        val callItem = AllowlistItem("id1", "联系人1", "phone", "+8613800138001", "描述1")
        val emailItem = AllowlistItem("id2", "邮箱1", "email", "test@example.com", "描述2")
        
        databaseManager.insertAllowlistItem(callItem, SelectFeatures.callProtection)
        databaseManager.insertAllowlistItem(emailItem, SelectFeatures.emailProtection)
        
        // When: 查询callProtection的items
        val callItems = databaseManager.getAllowlistItems(SelectFeatures.callProtection)
        
        // Then: 只返回callProtection的item
        assertThat(callItems).hasSize(1)
        assertThat(callItems[0].id).isEqualTo("id1")
        assertThat(callItems[0].value).isEqualTo("+8613800138001")
    }

    @Test
    fun `test update allowlist item`() {
        // Given: 插入一个item
        val originalItem = AllowlistItem("id1", "原名称", "phone", "+8613800138000", "原描述")
        databaseManager.insertAllowlistItem(originalItem, SelectFeatures.callProtection)
        
        // When: 更新item
        val updatedItem = AllowlistItem("id1", "新名称", "phone", "+8613800138000", "新描述")
        val updateCount = databaseManager.updateAllowlistItem(updatedItem, SelectFeatures.callProtection)
        
        // Then: 更新成功,返回1
        assertThat(updateCount).isEqualTo(1)
        
        // 验证数据已更新
        val items = databaseManager.getAllowlistItems(SelectFeatures.callProtection)
        assertThat(items[0].name).isEqualTo("新名称")
        assertThat(items[0].description).isEqualTo("新描述")
    }

    @Test
    fun `test delete allowlist item`() {
        // Given: 插入一个item
        val item = AllowlistItem("id1", "联系人", "phone", "+8613800138000", "描述")
        databaseManager.insertAllowlistItem(item, SelectFeatures.callProtection)
        
        // When: 删除item
        val deleteCount = databaseManager.deleteAllowlistItem("id1")
        
        // Then: 删除成功,返回1
        assertThat(deleteCount).isEqualTo(1)
        
        // 验证数据已删除
        val items = databaseManager.getAllowlistItems(SelectFeatures.callProtection)
        assertThat(items).isEmpty()
    }

    @Test
    fun `test check value in allowlist`() {
        // Given: 插入一个item
        val item = AllowlistItem("id1", "联系人", "phone", "+8613800138000", "描述")
        databaseManager.insertAllowlistItem(item, SelectFeatures.callProtection)
        
        // When & Then: 检查存在的值返回true
        assertThat(databaseManager.isValueInAllowlist("+8613800138000", SelectFeatures.callProtection))
            .isTrue()
        
        // When & Then: 检查不存在的值返回false
        assertThat(databaseManager.isValueInAllowlist("+8613900000000", SelectFeatures.callProtection))
            .isFalse()
    }

    @Test
    fun `test feature type isolation in allowlist`() {
        // Given: 同一value在不同feature中
        val item = AllowlistItem("id1", "通用值", "common", "test_value", "描述")
        databaseManager.insertAllowlistItem(item, SelectFeatures.callProtection)
        
        // When & Then: callProtection中存在
        assertThat(databaseManager.isValueInAllowlist("test_value", SelectFeatures.callProtection))
            .isTrue()
        
        // When & Then: emailProtection中不存在
        assertThat(databaseManager.isValueInAllowlist("test_value", SelectFeatures.emailProtection))
            .isFalse()
    }

    // ==================== AlertLog CRUD测试 ====================

    @Test
    fun `test insert alert log item successfully`() {
        // Given: 创建一个alert log item
        val item = AlertLogItem(
            time = "2025-10-28 10:30:00",
            type = "Suspicious Call",
            source = "+8613800138000",
            status = "Detected"
        )
        
        // When: 插入数据
        val result = databaseManager.insertAlertLogItem(item, SelectFeatures.callProtection)
        
        // Then: 插入成功(返回自增ID>0)
        assertThat(result).isGreaterThan(0)
    }

    @Test
    fun `test get alert log items by feature type`() {
        // Given: 插入多个不同feature的alert logs
        val callLog = AlertLogItem("2025-10-28 10:00:00", "Call", "+138", "Detected")
        val emailLog = AlertLogItem("2025-10-28 11:00:00", "Email", "spam@test.com", "Blocked")
        
        databaseManager.insertAlertLogItem(callLog, SelectFeatures.callProtection)
        databaseManager.insertAlertLogItem(emailLog, SelectFeatures.emailProtection)
        
        // When: 查询callProtection的logs
        val callLogs = databaseManager.getAlertLogItems(SelectFeatures.callProtection)
        
        // Then: 只返回callProtection的log
        assertThat(callLogs).hasSize(1)
        assertThat(callLogs[0].type).isEqualTo("Call")
    }

    @Test
    fun `test get alert log count`() {
        // Given: 插入3个call protection logs
        repeat(3) { index ->
            val log = AlertLogItem("2025-10-28 10:$index:00", "Call$index", "+138$index", "Detected")
            databaseManager.insertAlertLogItem(log, SelectFeatures.callProtection)
        }
        
        // When: 获取count
        val count = databaseManager.getAlertLogCount(SelectFeatures.callProtection)
        
        // Then: 返回3
        assertThat(count).isEqualTo(3)
    }

    @Test
    fun `test cleanup old alert logs`() {
        // Given: 插入5个logs
        repeat(5) { index ->
            val log = AlertLogItem("2025-10-28 10:$index:00", "Call$index", "+138$index", "Detected")
            databaseManager.insertAlertLogItem(log, SelectFeatures.callProtection)
        }
        
        // When: 清理,只保留最新2个
        val deletedCount = databaseManager.cleanupOldAlertLogs(SelectFeatures.callProtection, keepCount = 2)
        
        // Then: 删除了3个
        assertThat(deletedCount).isEqualTo(3)
        
        // 验证剩余2个
        val remainingLogs = databaseManager.getAlertLogItems(SelectFeatures.callProtection)
        assertThat(remainingLogs).hasSize(2)
    }

    // ==================== ContentLog CRUD测试 ====================

    @Test
    fun `test insert content log item successfully`() {
        // Given: 创建一个content log item
        val item = ContentLogItem(
            type = "Email",
            timestamp = System.currentTimeMillis(),
            content = "Phishing email content..."
        )
        
        // When: 插入数据
        val result = databaseManager.insertContentLogItem(item)
        
        // Then: 插入成功(返回自增ID>0)
        assertThat(result).isGreaterThan(0)
    }

    @Test
    fun `test get content log items by type`() {
        // Given: 插入不同类型的content logs
        val emailContent = ContentLogItem(0, "Email", System.currentTimeMillis(), "Email content")
        val zoomContent = ContentLogItem(0, "Zoom", System.currentTimeMillis(), "Zoom content")
        
        databaseManager.insertContentLogItem(emailContent)
        databaseManager.insertContentLogItem(zoomContent)
        
        // When: 查询Email类型
        val emailLogs = databaseManager.getContentLogItems("Email")
        
        // Then: 只返回Email类型
        assertThat(emailLogs).hasSize(1)
        assertThat(emailLogs[0].type).isEqualTo("Email")
        assertThat(emailLogs[0].content).isEqualTo("Email content")
    }

    @Test
    fun `test get all content log items`() {
        // Given: 插入3个不同类型的content logs
        databaseManager.insertContentLogItem(ContentLogItem(0, "Email", System.currentTimeMillis(), "Email 1"))
        databaseManager.insertContentLogItem(ContentLogItem(0, "Zoom", System.currentTimeMillis(), "Zoom 1"))
        databaseManager.insertContentLogItem(ContentLogItem(0, "PhoneCall", System.currentTimeMillis(), "Call 1"))
        
        // When: 获取所有content logs
        val allLogs = databaseManager.getAllContentLogItems()
        
        // Then: 返回所有3个
        assertThat(allLogs).hasSize(3)
    }

    // ==================== 边界情况测试 ====================

    @Test
    fun `test get empty list when no data`() {
        // When: 查询空表
        val items = databaseManager.getAllowlistItems(SelectFeatures.callProtection)
        
        // Then: 返回空列表而非null
        assertThat(items).isNotNull()
        assertThat(items).isEmpty()
    }

    @Test
    fun `test delete non-existent item returns zero`() {
        // When: 删除不存在的item
        val deleteCount = databaseManager.deleteAllowlistItem("non_existent_id")
        
        // Then: 返回0
        assertThat(deleteCount).isEqualTo(0)
    }

    @Test
    fun `test update non-existent item returns zero`() {
        // Given: 一个不存在的item
        val item = AllowlistItem("non_existent", "Name", "phone", "+138", "Desc")
        
        // When: 更新
        val updateCount = databaseManager.updateAllowlistItem(item, SelectFeatures.callProtection)
        
        // Then: 返回0
        assertThat(updateCount).isEqualTo(0)
    }
}
