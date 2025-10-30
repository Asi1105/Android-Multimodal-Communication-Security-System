# BCR 录音自动拉取工具使用说明

## 📋 目录

- [功能特性](#功能特性)
- [前置要求](#前置要求)
- [快速开始](#快速开始)
- [详细使用](#详细使用)
- [常见问题](#常见问题)

---

## 🎯 功能特性

### 完整版脚本 (`pull_bcr_recordings.ps1`)

✅ 自动检测 ADB 连接和 Root 权限  
✅ 扫描并列出所有 BCR 录音文件  
✅ 显示文件详细信息（时间、大小）  
✅ 支持批量拉取多个文件  
✅ 自动重命名避免文件覆盖  
✅ 可选自动播放最新录音  
✅ 彩色输出，操作清晰  

### 简化版脚本 (`quick_pull.ps1`)

⚡ 一键拉取最新录音  
⚡ 自动打开文件所在目录  
⚡ 适合快速操作  

---

## 🔧 前置要求

### 1. Android 设备要求
- ✅ 已安装 [BCR (Basic Call Recorder)](https://github.com/chenxiaolong/BCR)
- ✅ 已 Root（Magisk）
- ✅ 启用 USB 调试
- ✅ 至少录制过一次通话

### 2. PC 要求
- ✅ Windows 10/11 with PowerShell 5.1+
- ✅ 已安装 Android SDK Platform Tools (ADB)
- ✅ ADB 已添加到系统 PATH

### 3. 检查 ADB 安装

```powershell
# 检查 ADB 版本
adb version

# 检查设备连接
adb devices
```

应该显示类似：
```
List of devices attached
XXXXXXXXXX      device
```

---

## 🚀 快速开始

### 方法 1: 使用简化版（推荐新手）

```powershell
# 进入脚本目录
cd E:\anticenter\scripts

# 执行快速拉取
.\quick_pull.ps1
```

**效果**：
- 自动拉取最新的录音文件
- 保存到 `.\bcr_recordings\` 目录
- 自动打开文件所在位置

---

### 方法 2: 使用完整版（更多功能）

#### 基本用法 - 拉取最新 1 个文件

```powershell
.\pull_bcr_recordings.ps1 -LatestOnly
```

#### 拉取最新 5 个文件（默认）

```powershell
.\pull_bcr_recordings.ps1
```

#### 拉取最新 10 个文件

```powershell
.\pull_bcr_recordings.ps1 -MaxFiles 10
```

#### 拉取并自动播放

```powershell
.\pull_bcr_recordings.ps1 -LatestOnly -AutoPlay
```

#### 指定输出目录

```powershell
.\pull_bcr_recordings.ps1 -OutputDir "D:\MyRecordings"
```

#### 组合使用

```powershell
# 拉取最新 3 个文件，保存到桌面，并自动播放最新的
.\pull_bcr_recordings.ps1 -MaxFiles 3 -OutputDir "$env:USERPROFILE\Desktop\recordings" -AutoPlay
```

---

## 📖 详细使用

### 参数说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `-OutputDir` | String | `.\bcr_recordings` | 输出目录路径 |
| `-MaxFiles` | Int | `5` | 最多拉取的文件数量 |
| `-LatestOnly` | Switch | `false` | 仅拉取最新的一个文件 |
| `-AutoPlay` | Switch | `false` | 自动播放最新的录音 |

### 运行流程

1. **检查连接**
   ```
   ✅ 已连接设备数量: 1
   ```

2. **验证 Root**
   ```
   ✅ Root 权限验证成功
   ```

3. **扫描文件**
   ```
   ℹ️  扫描 BCR 录音目录...
   ✅ 找到 3 个录音文件
   ```

4. **显示列表**
   ```
   📋 录音文件列表:
   ════════════════════════════════════════════════════════════════════════
   [1] 20251018_235749.847+1100_IN_0461301172.oga
       📅 时间: 2025-10-18 23:57:49 (2 小时前)
       📊 大小: 0.95 MB
   [2] 20251007_185923.297+1100_IN_0434557805.oga
       📅 时间: 2025-10-07 18:59:23 (12 天前)
       📊 大小: 1.23 MB
   ════════════════════════════════════════════════════════════════════════
   ```

5. **确认拉取**
   ```
   是否开始拉取? (Y/N): Y
   ```

6. **下载进度**
   ```
   ℹ️  开始拉取: 20251018_235749.847+1100_IN_0461301172.oga
   ℹ️    → 复制到临时目录...
   ℹ️    → 下载到 PC...
   ✅ 成功拉取: recording_20251018_235749.oga (951.19 KB)
   ```

7. **完成总结**
   ```
   ✅ 拉取完成！成功: 3 / 3
   
   📁 文件保存位置:
     • recording_20251018_235749.oga
     • recording_20251007_185923.oga
   
   📂 输出目录: E:\anticenter\bcr_recordings
   ```

---

## 🎵 播放录音

### 支持的播放器

1. **VLC Media Player** (推荐)
   - 支持所有格式 (.oga, .opus, .m4a)
   - 下载: https://www.videolan.org/

2. **Windows Media Player**
   - 可能需要安装 codec pack

3. **在线播放器**
   - 上传到 https://audio.online-convert.com/

### 格式转换（可选）

如果需要转换为 MP3：

```powershell
# 使用 ffmpeg (需先安装)
ffmpeg -i recording.oga recording.mp3
```

---

## 🔧 常见问题

### 1. "ADB 未找到或未安装"

**解决方案**：
```powershell
# 下载 Android SDK Platform Tools
# 解压后将路径添加到系统 PATH
# 例如: C:\platform-tools

# 验证安装
adb version
```

### 2. "未检测到已连接的设备"

**解决方案**：
- 检查 USB 线缆
- 启用 USB 调试（开发者选项）
- 运行 `adb devices` 确认连接
- 可能需要在手机上授权

### 3. "无法获取 Root 权限"

**解决方案**：
- 确保设备已 Root（Magisk）
- 运行脚本时在手机上授予 Root 权限
- 测试: `adb shell su -c "whoami"` 应返回 `root`

### 4. "未找到任何录音文件"

**解决方案**：
- 确保 BCR App 已安装
- 至少录制过一次通话
- 检查 BCR 设置中的存储位置

### 5. PowerShell 执行策略错误

**错误信息**：
```
无法加载文件 pull_bcr_recordings.ps1，因为在此系统上禁止运行脚本
```

**解决方案**：
```powershell
# 以管理员身份运行 PowerShell
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser

# 或者临时绕过（不推荐）
PowerShell -ExecutionPolicy Bypass -File .\pull_bcr_recordings.ps1
```

### 6. 文件名包含特殊字符导致错误

**说明**：
- 脚本会自动处理文件名
- 使用时间戳重命名避免冲突
- 原始文件名保留在设备上

---

## 🔄 自动化方案

### 方案 1: Windows 计划任务

每次设备连接时自动拉取最新录音：

1. 打开"任务计划程序"
2. 创建基本任务
3. 触发器：设备连接时（需配置）
4. 操作：运行 PowerShell 脚本

```powershell
# 任务操作配置
程序: PowerShell.exe
参数: -ExecutionPolicy Bypass -File "E:\anticenter\scripts\quick_pull.ps1"
```

### 方案 2: 批处理快捷方式

创建桌面快捷方式一键运行：

```batch
@echo off
cd /d E:\anticenter\scripts
powershell.exe -ExecutionPolicy Bypass -File ".\quick_pull.ps1"
pause
```

保存为 `拉取录音.bat`，双击即可运行。

---

## 📊 文件命名规则

### 原始文件名格式（BCR）
```
20251018_235749.847+1100_IN_0461301172.oga
│        │          │    │  │
│        │          │    │  └─ 电话号码
│        │          │    └──── 呼叫类型 (IN=来电, OUT=去电)
│        │          └───────── 时区
│        └──────────────────── 时间 (HH:mm:ss.SSS)
└───────────────────────────── 日期 (YYYY-MM-DD)
```

### 拉取后的文件名格式
```
recording_20251018_235749.oga
│         │        │
│         │        └─ 时间
│         └────────── 日期
└──────────────────── 前缀
```

或保留原文件名并添加时间戳：
```
20251018_235749.847+1100_IN_0461301172_20251019_020530.oga
                                          │
                                          └─ 拉取时间戳
```

---

## 🛠️ 高级用法

### 批量导出所有录音

```powershell
# 导出所有录音（不限制数量）
.\pull_bcr_recordings.ps1 -MaxFiles 999
```

### 定期备份脚本

```powershell
# backup_recordings.ps1
$backupDir = "D:\BCR_Backup\$(Get-Date -Format 'yyyy-MM')"

.\pull_bcr_recordings.ps1 -OutputDir $backupDir -MaxFiles 999

# 压缩备份
Compress-Archive -Path $backupDir -DestinationPath "$backupDir.zip"
```

### 与其他工具集成

```powershell
# 拉取后自动转换为 MP3
.\pull_bcr_recordings.ps1 -LatestOnly

$ogaFile = Get-ChildItem ".\bcr_recordings\*.oga" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$mp3File = $ogaFile.FullName -replace '\.oga$', '.mp3'

ffmpeg -i $ogaFile.FullName -codec:a libmp3lame -qscale:a 2 $mp3File
```

---

## 📝 日志记录

脚本会在控制台输出详细日志，如需保存日志：

```powershell
.\pull_bcr_recordings.ps1 | Tee-Object -FilePath "pull_log_$(Get-Date -Format 'yyyyMMdd_HHmmss').txt"
```

---

## 🤝 贡献与反馈

如果遇到问题或有改进建议，欢迎反馈！

---

## 📄 许可

MIT License

---

**Enjoy! 🎉**
