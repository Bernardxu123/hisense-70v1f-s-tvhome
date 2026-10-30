# 一键恢复默认桌面（把电视拉回"聚好看开机"的可控状态）
# 用途：如果开机后没有进桌面 / 黑屏 / 想看回聚好看，跑这个脚本即可。
param(
    [string]$Serial = '192.168.31.234:5555',
    [string]$Adb = 'D:\software\开心电视助手8.1版本By剑心\Release\adb.exe'
)
$ErrorActionPreference = 'Continue'

& $Adb connect $Serial | Out-Null
Start-Sleep -Seconds 1
$dev = & $Adb devices | Select-String $Serial
if (-not $dev) { Write-Host '设备未连接，检查电视是否开机、网络是否可达'; exit 1 }

$recover = @'
#!/system/bin/sh
LOG=/data/local/tmp/recover.log
exec > "$LOG" 2>&1
chmod 666 "$LOG"
echo "=== $(date) recover uid=$(id -u)"
pm enable --user 0 com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity 2>&1
sleep 1
am start -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1
sleep 3
echo "top=$(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity)"
echo "boot=$(getprop sys.boot_completed)"
'@

$tmp = Join-Path $env:TEMP 'tv-recover-rc.sh'
[System.IO.File]::WriteAllText($tmp, $recover.Replace("`r`n", "`n"), (New-Object System.Text.UTF8Encoding $false))
& $Adb push $tmp /data/local/misc/rc.sh | Out-Null
& $Adb shell 'chmod 644 /data/local/misc/rc.sh; setprop ctl.start autorun; echo TRIGGERED'
Start-Sleep -Seconds 25
& $Adb shell 'cat /data/local/tmp/recover.log'
