# AutoTether 설치/설정 스크립트 (Windows 10/11, PowerShell 5.1+)
#
# 이 PC에서 한 번 실행하면:
#   [PC]  USB 테더링 PC 식별용 방화벽 규칙 추가 (PC마다 1회 필요)
#   [폰]  adb 준비(없으면 자동 다운로드) → APK 설치 → 알림 허용 → 배터리 최적화 제외
#         → "기본 USB 구성 = USB 테더링" → 앱 실행(모니터링 자동 시작)
# 두 번째 PC부터는 폰 단계를 건너뛰어도 된다(S 키).

param([switch]$Elevated)

$ErrorActionPreference = 'Continue'   # adb의 stderr 출력이 오류로 취급되지 않도록
$Pkg = 'kr.across.autotether'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Apk = Join-Path $Here 'AutoTether.apk'
$LogFile = Join-Path $Here 'setup.log'

# ---------- 관리자 권한으로 재실행 ----------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    if ($Elevated) { Write-Host '관리자 권한을 얻지 못했습니다.' -ForegroundColor Red; Read-Host '엔터를 누르면 종료'; exit 1 }
    Start-Process powershell.exe -Verb RunAs -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$($MyInvocation.MyCommand.Path)`"", '-Elevated')
    exit 0
}

# 예기치 않은 오류: 창이 바로 닫히지 않게 메시지를 보여 주고 대기
trap {
    Write-Host ''
    Write-Host "오류: $_" -ForegroundColor Red
    try { Stop-Transcript | Out-Null } catch {}
    Read-Host '엔터를 누르면 종료'
    exit 1
}

try { Stop-Transcript | Out-Null } catch {}
Start-Transcript -Path $LogFile -Force | Out-Null
$Host.UI.RawUI.WindowTitle = 'AutoTether 설치'

function Step($n, $text) { Write-Host ''; Write-Host "[$n] $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "    OK  $text" -ForegroundColor Green }
function Warn($text) { Write-Host "    !!  $text" -ForegroundColor Yellow }
function Info($text) { Write-Host "        $text" }

Write-Host '==============================================' -ForegroundColor Cyan
Write-Host '  AutoTether 설치 / 설정' -ForegroundColor Cyan
Write-Host '==============================================' -ForegroundColor Cyan

# ---------- 1. PC 방화벽 ----------
Step 1 'PC 방화벽: USB 테더링 연결에서 PC 이름 조회(NetBIOS UDP 137) 허용'
$ruleName = 'AutoTether-NetBIOS-In'
Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction Stop
New-NetFirewallRule -Name $ruleName -DisplayName 'AutoTether - USB 테더링 PC 식별 (NetBIOS UDP 137)' `
    -Description 'AutoTether 앱이 USB 테더링으로 연결된 이 PC의 이름을 확인할 수 있게 합니다. 유선 인터페이스의 로컬 서브넷에서만 허용.' `
    -Direction Inbound -Protocol UDP -LocalPort 137 -RemoteAddress LocalSubnet `
    -InterfaceType Wired -Profile Any -Action Allow -ErrorAction Stop | Out-Null
Ok '방화벽 규칙 추가됨 (유선 인터페이스 / 로컬 서브넷 / UDP 137)'

# NetBIOS over TCP/IP가 꺼진 USB 테더링 어댑터가 있으면 기본값(사용)으로 되돌린다
Get-CimInstance Win32_NetworkAdapterConfiguration |
    Where-Object { $_.Description -match 'NDIS|NCM' -and $_.TcpipNetbiosOptions -eq 2 } |
    ForEach-Object {
        Invoke-CimMethod -InputObject $_ -MethodName SetTcpipNetbios -Arguments @{ TcpipNetbiosOptions = [uint32]0 } | Out-Null
        Ok "NetBIOS 사용으로 변경: $($_.Description)"
    }

# ---------- 2. adb 준비 ----------
Step 2 'adb(Android 디버그 도구) 준비'
function Find-Adb {
    $candidates = @(
        (Join-Path $Here 'platform-tools\adb.exe'),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    )
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}
$Adb = Find-Adb
if (-not $Adb) {
    Info 'adb가 없어 Google 공식 platform-tools를 내려받습니다...'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -ErrorAction Stop -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $Here -Force -ErrorAction Stop
    Remove-Item $zip -Force
    $Adb = Join-Path $Here 'platform-tools\adb.exe'
}
Ok $Adb

function AdbRun([string[]]$a) {
    $out = & $Adb @a 2>&1 | Out-String
    return $out.Trim()
}
function AdbState {
    $lines = (& $Adb devices 2>$null) | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
    if (-not $lines) { return 'none' }
    if (@($lines).Count -gt 1) { return 'multi' }
    return ($lines -split '\s+')[1]
}

# ---------- 3. 폰 연결 ----------
Step 3 '폰 연결 (USB 디버깅)'
Info '두 번째 PC부터 PC 설정만 하려면 S 키를 누르세요.'
& $Adb start-server 2>$null | Out-Null
$skipPhone = $false
$lastShown = ''
while ($true) {
    $state = AdbState
    if ($state -eq 'device') { break }
    if ($state -ne $lastShown) {
        $lastShown = $state
        switch ($state) {
            'none' {
                Warn '폰이 보이지 않습니다. 폰에서 아래를 진행하세요 (최초 1회):'
                Info '  1) 설정 > 휴대전화 정보 > 소프트웨어 정보 > "빌드번호"를 7번 탭 (개발자 옵션 켜짐)'
                Info '  2) 설정 > 개발자 옵션 > "USB 디버깅" 켜기'
                Info '  3) USB 케이블로 이 PC에 연결 (데이터 전송 가능한 케이블)'
                Info '  4) 삼성 폰이 계속 안 보이면 Samsung USB 드라이버 설치:'
                Info '     https://developer.samsung.com/android-usb-driver   (D 키: 페이지 열기)'
            }
            'unauthorized' {
                Warn '폰 화면의 "USB 디버깅을 허용하시겠습니까?"에서 "이 컴퓨터에서 항상 허용" 체크 후 [허용]을 누르세요.'
                Info '창이 안 보이면 폰 잠금을 풀거나 케이블을 다시 꽂으세요.'
            }
            'offline' { Warn '폰이 offline 상태입니다. 케이블을 다시 꽂아 주세요.' }
            'multi' { Warn '안드로이드 기기가 2대 이상 연결되어 있습니다. 설정할 폰 1대만 연결하세요.' }
        }
        Info '기다리는 중... (S: 폰 설정 건너뛰기)'
    }
    $deadline = (Get-Date).AddSeconds(2)
    while ((Get-Date) -lt $deadline) {
        if ([Console]::KeyAvailable) {
            $k = [Console]::ReadKey($true).Key
            if ($k -eq 'S') { $skipPhone = $true; break }
            if ($k -eq 'D') { Start-Process 'https://developer.samsung.com/android-usb-driver' }
        }
        Start-Sleep -Milliseconds 200
    }
    if ($skipPhone) { break }
    if ($state -eq 'offline') { & $Adb reconnect offline 2>$null | Out-Null }
}

if ($skipPhone) {
    Warn '폰 설정을 건너뛰었습니다 (PC 방화벽만 설정됨).'
} else {
    $model = AdbRun @('shell', 'getprop', 'ro.product.model')
    $sdk = [int](AdbRun @('shell', 'getprop', 'ro.build.version.sdk'))
    Ok "연결됨: $model (API $sdk)"
    if ($sdk -lt 26) { Warn 'Android 8.0 미만은 지원하지 않습니다.'; Stop-Transcript | Out-Null; Read-Host '엔터를 누르면 종료'; exit 1 }

    # ---------- 4. APK 설치 ----------
    Step 4 '앱 설치'
    if (-not (Test-Path $Apk)) { throw "AutoTether.apk 가 없습니다: $Apk" }
    $r = AdbRun @('install', '-r', $Apk)
    if ($r -notmatch 'Success') {
        if ($r -match 'UPDATE_INCOMPATIBLE|SIGNATURES') {
            Warn '다른 서명으로 설치된 AutoTether가 있습니다. 삭제 후 다시 설치합니다 (기존 기록 삭제됨).'
            AdbRun @('uninstall', $Pkg) | Out-Null
            $r = AdbRun @('install', $Apk)
        }
        if ($r -notmatch 'Success') { throw "설치 실패: $r" }
    }
    Ok '설치 완료'

    # ---------- 5. 권한 / 시스템 설정 ----------
    Step 5 '권한 및 시스템 설정'
    if ($sdk -ge 33) {
        AdbRun @('shell', 'pm', 'grant', $Pkg, 'android.permission.POST_NOTIFICATIONS') | Out-Null
        Ok '알림 허용'
    }
    AdbRun @('shell', 'dumpsys', 'deviceidle', 'whitelist', "+$Pkg") | Out-Null
    Ok '배터리 최적화 제외'

    # 잠금 해제 상태에서 USB 연결 시 기본 기능 = 테더링 (개발자 옵션 "기본 USB 구성")
    $fn = 'rndis'
    $r = AdbRun @('shell', 'svc', 'usb', 'setScreenUnlockedFunctions', $fn)
    $check = AdbRun @('shell', 'dumpsys', 'usb')
    if ($check -notmatch 'screen_unlocked_functions=RNDIS') {
        $fn = 'ncm'
        AdbRun @('shell', 'svc', 'usb', 'setScreenUnlockedFunctions', $fn) | Out-Null
        $check = AdbRun @('shell', 'dumpsys', 'usb')
    }
    $usbDefaultOk = $check -match 'screen_unlocked_functions=(RNDIS|NCM)'
    if ($usbDefaultOk) {
        Ok "기본 USB 구성 = USB 테더링 ($fn)"
        Info '삼성 "기본 USB 구성" 화면에는 "폰 충전만"으로 보일 수 있으나 정상입니다. 그 화면에서 다른 항목을 누르지 마세요.'
    } else {
        Warn '기본 USB 구성을 자동 설정하지 못했습니다. 폰에서 개발자 옵션 > 기본 USB 구성 > USB 테더링 을 선택하세요.'
    }

    # ---------- 6. 앱 실행 ----------
    Step 6 '앱 실행 (모니터링 자동 시작)'
    AdbRun @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    $startArgs = @('shell', 'am', 'start', '-n', "$Pkg/.MainActivity")
    if ($usbDefaultOk) {
        $startArgs += @('--el', 'kr.across.autotether.USB_DEFAULT_SET', [string][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    }
    AdbRun $startArgs | Out-Null
    Ok '앱 실행됨'
}

Write-Host ''
Write-Host '==============================================' -ForegroundColor Green
Write-Host '  완료' -ForegroundColor Green
Write-Host '==============================================' -ForegroundColor Green
Info '- 폰 잠금을 푼 상태로 PC에 USB를 연결하면 테더링이 자동으로 켜집니다.'
Info '- 앱 화면 "초기 설정" 항목이 모두 체크(✓)인지 확인하세요.'
Info '- 다른 PC에서도 쓰려면 그 PC에서 이 스크립트를 한 번 실행하세요 (S 키로 PC 설정만).'
Info "- 로그: $LogFile"
Stop-Transcript | Out-Null
Read-Host '엔터를 누르면 종료'
