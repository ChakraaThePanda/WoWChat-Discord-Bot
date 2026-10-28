# Runs the bot hidden with a system tray icon. Launched by Start WoWChat.bat with -Detach.
param([switch]$Detach)

# Relaunch hidden from the start. "-WindowStyle Hidden" only hides an already-open console, which
# Windows Terminal (the Windows 11 default) leaves on screen as an empty window.
if ($Detach) {
    Start-Process powershell -WindowStyle Hidden -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`""
    exit
}

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

# Only one tray (and so one bot) at a time
$mutex = New-Object System.Threading.Mutex($false, "Local\WoWChatBotTray")
if (-not $mutex.WaitOne(0)) {
    [System.Windows.Forms.MessageBox]::Show("WoWChat is already running in the system tray.", "WoWChat") | Out-Null
    exit
}

$dir = Split-Path $PSScriptRoot
$lib = Join-Path $dir "lib"
$log = Join-Path $dir "data\bot.log"
$oldLog = Join-Path $dir "data\bot.old.log"
# Start WoWChat.bat finds Java (and installs it) and passes it on here
$java = if ($env:WOWCHAT_JAVA) { $env:WOWCHAT_JAVA } else { "java" }
New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
$script:bot = $null
$script:cmd = $null

function Start-Bot {
    # A bot from this folder still running here was orphaned by a tray that died (only one tray
    # runs per folder). Stop it, or it keeps bot.log locked and logs into WoW a second time.
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($lib) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

    # A fresh log on every start or restart from the tray. The watchdog writes it, and starts
    # it over past 5 MB, keeping the previous part in bot.old.log.
    Remove-Item -LiteralPath $log, $oldLog -Force -ErrorAction SilentlyContinue

    # The watchdog runs the bot as its own child process and restarts it if it gets stuck
    $watchdog = Join-Path $lib "watchdog.jar"
    $jar      = Join-Path $lib "wowchat.jar"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName         = "cmd.exe"
    $psi.Arguments        = "/s /c `"`"$java`" -Dfile.encoding=UTF-8 -jar `"$watchdog`" --log `"$log`" `"$jar`" wowchat.conf`""
    $psi.WorkingDirectory = $dir
    $psi.UseShellExecute  = $false
    $psi.CreateNoWindow   = $true
    $script:cmd = [System.Diagnostics.Process]::Start($psi)
    $script:bot = $script:cmd
    # Track java itself, not the cmd wrapper: cmd can exit while java keeps running
    for ($i = 0; $i -lt 40; $i++) {
        $child = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($script:cmd.Id) AND Name='java.exe'"
        if ($child) {
            $proc = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
            if ($proc) {
                $null = $proc.Handle # Without an open handle, ExitCode is empty once java exits
                $script:bot = $proc
            }
            break
        }
        if ($script:cmd.HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
    $tray.Text = "WoWChat"
    $tray.Icon = $iconRunning
}

function Stop-Bot {
    # Kill the watchdog with the bot under it, and the cmd wrapper (any may already be gone)
    foreach ($p in @($script:bot, $script:cmd)) {
        if ($p -and -not $p.HasExited) {
            & taskkill /PID $p.Id /T /F | Out-Null
            $p.WaitForExit(5000) | Out-Null
        }
    }
}

$tray = New-Object System.Windows.Forms.NotifyIcon
try {
    # Bot logo if present, otherwise java's icon
    $png = Join-Path $PSScriptRoot "icon.png"
    if (Test-Path $png) {
        $src = [System.Drawing.Image]::FromFile($png)
    } else {
        $javaPath = if (Test-Path $java) { $java } else { (Get-Command java).Source }
        $src = [System.Drawing.Icon]::ExtractAssociatedIcon($javaPath).ToBitmap()
    }
    $bmp = New-Object System.Drawing.Bitmap $src, 64, 64
    $src.Dispose() # Image.FromFile keeps icon.png locked until disposed
    $iconRunning = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())

    # Stopped variant: same logo with a red dot in the bottom-right corner
    $bmpStopped = New-Object System.Drawing.Bitmap $bmp
    $g = [System.Drawing.Graphics]::FromImage($bmpStopped)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.FillEllipse([System.Drawing.Brushes]::White, 30, 30, 34, 34)
    $g.FillEllipse([System.Drawing.Brushes]::Red, 34, 34, 26, 26)
    $g.Dispose()
    $iconStopped = [System.Drawing.Icon]::FromHandle($bmpStopped.GetHicon())
} catch {
    $iconRunning = [System.Drawing.SystemIcons]::Application
    $iconStopped = [System.Drawing.SystemIcons]::Error
}
$tray.Icon = $iconRunning
$tray.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$showLog = $menu.Items.Add("Show log")
$restart = $menu.Items.Add("Restart bot")
$menu.Items.Add("-") | Out-Null
$quit    = $menu.Items.Add("Stop bot and exit")
$tray.ContextMenuStrip = $menu

$openLog = {
    # The last 200 lines across bot.old.log and bot.log, then new lines as they come. Reads
    # without holding the file open, and starts from the top when the bot empties it at 5 MB.
    $cmd = @"
`$Host.UI.RawUI.WindowTitle = 'WoWChat Bot log'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
`$log = '$($log -replace "'", "''")'
`$old = '$($oldLog -replace "'", "''")'
''
'  ============================================'
'   WoWChat Discord Bot'
'  ============================================'
''
`$lines = @()
if (Test-Path -LiteralPath `$log) { `$lines = @(Get-Content -LiteralPath `$log -Encoding UTF8 -Tail 200) }
if (`$lines.Count -lt 200 -and (Test-Path -LiteralPath `$old)) {
    `$lines = @(Get-Content -LiteralPath `$old -Encoding UTF8 -Tail (200 - `$lines.Count)) + `$lines
}
`$lines
`$pos = if (Test-Path -LiteralPath `$log) { (Get-Item -LiteralPath `$log).Length } else { 0 }
while (`$true) {
    Start-Sleep -Milliseconds 500
    if (-not (Test-Path -LiteralPath `$log)) { `$pos = 0; continue }
    `$len = (Get-Item -LiteralPath `$log).Length
    if (`$len -lt `$pos) { `$pos = 0 }
    if (`$len -le `$pos) { continue }
    try {
        `$fs = [IO.File]::Open(`$log, 'Open', 'Read', 'ReadWrite, Delete')
        `$null = `$fs.Seek(`$pos, 'Begin')
        `$reader = New-Object IO.StreamReader(`$fs, [Text.Encoding]::UTF8)
        [Console]::Write(`$reader.ReadToEnd())
        `$pos = `$fs.Position
        `$reader.Close()
    } catch {}
}
"@
    # Encoded so spacing and quotes survive Start-Process argument joining
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
    Start-Process powershell -ArgumentList "-NoProfile", "-NoExit", "-EncodedCommand", $enc
}
$showLog.add_Click($openLog)
$tray.add_DoubleClick($openLog)

$restart.add_Click({
    Stop-Bot
    Start-Bot
    $tray.ShowBalloonTip(2000, "WoWChat", "Bot restarted.", "Info")
})

$quit.add_Click({
    $timer.Stop()
    Stop-Bot
    $tray.Visible = $false
    $tray.Dispose()
    [System.Windows.Forms.Application]::Exit()
})

# Notify if the bot dies on its own
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 2000
$timer.add_Tick({
    if ($script:bot -and $script:bot.HasExited -and $tray.Text -notlike "*Stopped*") {
        $tray.Text = "WoWChat - Stopped"
        $tray.Icon = $iconStopped
        $tray.ShowBalloonTip(5000, "WoWChat", "Bot stopped (exit code $($script:bot.ExitCode)). Right-click the tray icon to view the log or restart.", "Warning")
    }
})

Start-Bot
$timer.Start()
$tray.ShowBalloonTip(3000, "WoWChat", "Bot is running in the tray. Right-click for options.", "Info")
[System.Windows.Forms.Application]::Run()
