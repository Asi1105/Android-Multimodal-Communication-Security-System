# ========================================
# Real-Time Audio Snapshot Monitor
# ========================================
#
# Purpose:
#   Continuously monitors the AntiCenter app's WAV cache directory and automatically
#   downloads audio chunks generated during phone calls.
#
# How It Works:
#   - Polls device cache every 3 seconds
#   - Detects new WAV files (10-second audio chunks)
#   - Downloads to PC before app cleanup
#   - Tracks downloaded files to avoid duplicates
#
# Requirements:
#   - Android device with root access
#   - ADB installed and device connected
#   - AntiCenter app running with active call
#
# Usage:
#   .\monitor_simple.ps1
#   Press Ctrl+C to stop monitoring
#
# Output:
#   Downloaded files saved to: .\snapshots\
#   Folder opens automatically on exit
#
# ========================================

Write-Host "🎵 Starting Snapshot Monitor..." -ForegroundColor Cyan

# Configuration
$outputDir = ".\snapshots"                                                    # Local output directory
$wavDir = "/data/data/com.example.anticenter/cache/bcr_wav_chunks"          # Device cache directory

# Create output directory if it doesn't exist
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

# Tracking variables
$downloaded = @{}                                                             # Hash table to track downloaded files
$count = 0                                                                    # Counter for successful downloads

# Display configuration
Write-Host "✅ Device directory: $wavDir" -ForegroundColor Green
Write-Host "✅ Output directory: $outputDir" -ForegroundColor Green
Write-Host "🔄 Monitoring started... (Press Ctrl+C to stop)`n" -ForegroundColor Yellow

try {
    # Main monitoring loop
    while ($true) {
        # Query device for WAV files in cache directory
        # Uses root access to list files in app's private cache
        $files = adb shell su -c "ls -1 $wavDir/*.wav 2>/dev/null" 2>&1
        
        # Process files if any were found
        if (-not [string]::IsNullOrWhiteSpace($files)) {
            foreach ($filePath in $files) {
                $filePath = $filePath.Trim()
                $fileName = Split-Path $filePath -Leaf
                
                # Skip files that have already been downloaded
                if ($downloaded.ContainsKey($fileName)) {
                    continue
                }
                
                Write-Host "[$(Get-Date -Format 'HH:mm:ss')] 📥 New snapshot detected: $fileName" -ForegroundColor Cyan
                
                # Step 1: Copy file to accessible temporary location
                # WAV is in app's private cache (/data/data/...) which requires root
                # Copy to /sdcard/Download where ADB can access without root
                $temp = "/sdcard/Download/temp_$fileName"
                adb shell su -c "cp '$filePath' '$temp' && chmod 666 '$temp'" | Out-Null
                
                # Step 2: Pull file from device to PC using ADB
                $local = Join-Path $outputDir $fileName
                adb pull $temp $local 2>&1 | Out-Null
                
                # Step 3: Clean up temporary file on device
                adb shell rm -f $temp 2>&1 | Out-Null
                
                # Verify download success
                if (Test-Path $local) {
                    $size = [math]::Round((Get-Item $local).Length / 1KB, 2)
                    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] ✅ Download successful: $fileName ($size KB)" -ForegroundColor Green
                    
                    # Mark as downloaded to prevent re-downloading
                    $downloaded[$fileName] = $true
                    $count++
                } else {
                    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] ❌ Download failed" -ForegroundColor Red
                }
            }
        }
        
        # Wait 3 seconds before next polling cycle
        Start-Sleep -Seconds 3
    }
}
finally {
    # Cleanup and exit handling (triggered by Ctrl+C)
    Write-Host "`n✅ Downloaded $count snapshot(s) to: $outputDir" -ForegroundColor Green
    
    # Automatically open output folder in Windows Explorer
    Start-Process "explorer.exe" "/select,`"$(Resolve-Path $outputDir)`""
}
