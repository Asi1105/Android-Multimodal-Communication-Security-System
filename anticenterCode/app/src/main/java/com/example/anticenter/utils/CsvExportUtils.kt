package com.example.anticenter.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.anticenter.data.ContentLogItem
import com.opencsv.CSVWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * CSV导出工具类
 * 用于将ContentLogItem数据导出为CSV文件并提供分享功能
 */
class CsvExportUtils {
    
    companion object {
        private val CSV_HEADER = arrayOf("ID", "Type", "Timestamp", "Formatted_Time", "Content")
        
        /**
         * 将ContentLogItem列表导出为CSV文件
         * 
         * @param context Android上下文
         * @param items 要导出的ContentLogItem列表
         * @param type 数据类型（用于文件命名）
         * @return 生成的CSV文件，如果导出失败则返回null
         */
        fun exportToCsv(
            context: Context,
            items: List<ContentLogItem>,
            type: String
        ): File? {
            return try {
                android.util.Log.d("CsvExportUtils", "Starting CSV export for type: $type, items count: ${items.size}")
                
                // 创建文件名（包含类型和时间戳）
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "AntiCenter_${type}_Export_$timestamp.csv"
                
                // 直接保存到公共Downloads目录，避免FileProvider权限问题
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                android.util.Log.d("CsvExportUtils", "Creating CSV file: ${file.absolutePath}")
                
                // 确保Downloads目录存在
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                // 创建CSV写入器
                val writer = CSVWriter(
                    FileWriter(file),
                    CSVWriter.DEFAULT_SEPARATOR,
                    CSVWriter.DEFAULT_QUOTE_CHARACTER,
                    CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                    CSVWriter.DEFAULT_LINE_END
                )
                
                // 写入表头
                writer.writeNext(CSV_HEADER)
                
                // 写入数据
                items.forEach { item ->
                    try {
                        val formattedTime = try {
                            item.getFormattedTime()
                        } catch (e: Exception) {
                            android.util.Log.w("CsvExportUtils", "Failed to format time for item ${item.id}, using timestamp", e)
                            item.timestamp.toString()
                        }
                        
                        val row = arrayOf(
                            item.id.toString(),
                            item.type,
                            item.timestamp.toString(),
                            formattedTime,
                            item.content
                        )
                        writer.writeNext(row)
                        android.util.Log.d("CsvExportUtils", "Written row: ${item.id}, ${item.type}, ${item.content.take(50)}...")
                    } catch (e: Exception) {
                        android.util.Log.e("CsvExportUtils", "Failed to write row for item ${item.id}", e)
                    }
                }
                
                // 确保数据写入磁盘
                writer.flush()
                
                // 关闭写入器
                writer.close()
                
                // 验证文件大小
                val fileLength = file.length()
                android.util.Log.d("CsvExportUtils", "CSV file exported successfully: ${file.absolutePath}, size: ${fileLength} bytes")
                
                if (fileLength == 0L) {
                    android.util.Log.e("CsvExportUtils", "Warning: CSV file is empty!")
                    return null
                }
                
                // 通知媒体扫描器更新文件
                try {
                    val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    scanIntent.data = Uri.fromFile(file)
                    context.sendBroadcast(scanIntent)
                    android.util.Log.d("CsvExportUtils", "Media scanner notified for file: ${file.name}")
                } catch (e: Exception) {
                    android.util.Log.w("CsvExportUtils", "Failed to notify media scanner", e)
                }
                
                file
                
            } catch (e: Exception) {
                android.util.Log.e("CsvExportUtils", "Failed to export CSV file", e)
                null
            }
        }
        
        /**
         * 创建分享Intent，调用系统分享菜单
         * 
         * @param context Android上下文
         * @param file 要分享的CSV文件
         * @param type 数据类型（用于分享标题）
         * @return 分享Intent，如果创建失败则返回null
         */
        fun createShareIntent(
            context: Context,
            file: File,
            type: String
        ): Intent? {
            return try {
                // 创建文档保存Intent，让用户选择保存位置
                val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setType("text/csv")
                    putExtra(Intent.EXTRA_TITLE, file.name)
                }
                
                // 创建传统分享Intent作为备选
                val fileUri = Uri.fromFile(file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    this.type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    putExtra(Intent.EXTRA_SUBJECT, "AntiCenter $type Data Export")
                    putExtra(Intent.EXTRA_TEXT, "Exported $type protection data from AntiCenter app.")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                // 创建选择器，优先显示保存选项
                val chooser = Intent.createChooser(saveIntent, "Save CSV file to...")
                
                // 添加传统分享选项
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
                
                chooser
                
            } catch (e: Exception) {
                android.util.Log.e("CsvExportUtils", "Failed to create share intent", e)
                null
            }
        }
        
        /**
         * 创建文档保存Intent，让用户选择保存位置
         */
        fun createSaveDocumentIntent(
            context: Context,
            fileName: String
        ): Intent {
            return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                setType("text/csv")
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
        }
        
        /**
         * 将CSV内容写入到用户选择的URI位置
         */
        fun writeCsvToUri(
            context: Context,
            uri: Uri,
            items: List<ContentLogItem>
        ): Boolean {
            return try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val writer = outputStream.writer()
                    val csvWriter = CSVWriter(
                        writer,
                        CSVWriter.DEFAULT_SEPARATOR,
                        CSVWriter.DEFAULT_QUOTE_CHARACTER,
                        CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                        CSVWriter.DEFAULT_LINE_END
                    )
                    
                    // 写入表头
                    csvWriter.writeNext(CSV_HEADER)
                    
                    // 写入数据
                    items.forEach { item ->
                        val formattedTime = try {
                            item.getFormattedTime()
                        } catch (e: Exception) {
                            item.timestamp.toString()
                        }
                        
                        val row = arrayOf(
                            item.id.toString(),
                            item.type,
                            item.timestamp.toString(),
                            formattedTime,
                            item.content
                        )
                        csvWriter.writeNext(row)
                    }
                    
                    csvWriter.flush()
                    csvWriter.close()
                    
                    android.util.Log.d("CsvExportUtils", "CSV written to user-selected location: $uri")
                    true
                }
            } catch (e: Exception) {
                android.util.Log.e("CsvExportUtils", "Failed to write CSV to URI: $uri", e)
                false
            } ?: false
        }
        
        /**
         * 导出并分享CSV文件（一步完成）
         * 
         * @param context Android上下文
         * @param items 要导出的ContentLogItem列表
         * @param type 数据类型
         * @param onExportComplete 导出完成回调，参数为是否成功
         */
        /**
         * 已弃用：使用 createSaveDocumentIntent 和 writeCsvToUri 代替
         */
        @Deprecated("Use createSaveDocumentIntent and writeCsvToUri instead")
        fun exportAndShare(
            context: Context,
            items: List<ContentLogItem>,
            type: String,
            onExportComplete: (Boolean) -> Unit = {}
        ) {
            // 保留向后兼容性，但推荐使用新方法
            try {
                val csvFile = exportToCsv(context, items, type)
                if (csvFile != null) {
                    val shareIntent = createShareIntent(context, csvFile, type)
                    if (shareIntent != null) {
                        context.startActivity(shareIntent)
                        onExportComplete(true)
                        return
                    }
                }
                onExportComplete(false)
            } catch (e: Exception) {
                android.util.Log.e("CsvExportUtils", "Legacy export failed", e)
                onExportComplete(false)
            }
        }
        
        /**
         * 获取导出文件的统计信息
         * 
         * @param items ContentLogItem列表
         * @return 包含统计信息的Map
         */
        fun getExportStats(items: List<ContentLogItem>): Map<String, Any> {
            val stats = mutableMapOf<String, Any>()
            
            stats["totalItems"] = items.size
            
            if (items.isNotEmpty()) {
                val oldestTimestamp = items.minByOrNull { it.timestamp }?.timestamp ?: 0L
                val newestTimestamp = items.maxByOrNull { it.timestamp }?.timestamp ?: 0L
                
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                stats["oldestItem"] = formatter.format(Date(oldestTimestamp))
                stats["newestItem"] = formatter.format(Date(newestTimestamp))
                
                // 内容长度统计
                val contentLengths = items.map { it.content.length }
                stats["averageContentLength"] = if (contentLengths.isNotEmpty()) 
                    contentLengths.average().toInt() else 0
                stats["maxContentLength"] = contentLengths.maxOrNull() ?: 0
                stats["minContentLength"] = contentLengths.minOrNull() ?: 0
            }
            
            return stats
        }
        
        /**
         * 验证导出数据的完整性
         * 
         * @param items ContentLogItem列表
         * @return 验证结果，包含是否有效和问题描述
         */
        fun validateExportData(items: List<ContentLogItem>): Pair<Boolean, String> {
            if (items.isEmpty()) {
                return Pair(false, "No data to export")
            }
            
            val invalidItems = items.filter { item ->
                item.type.isBlank() || item.content.isBlank()
            }
            
            if (invalidItems.isNotEmpty()) {
                return Pair(false, "Found ${invalidItems.size} items with missing type or content")
            }
            
            return Pair(true, "Data validation passed")
        }
    }
}