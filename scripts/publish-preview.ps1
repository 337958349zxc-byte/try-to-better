param(
    [Parameter(Mandatory = $true)][string]$ChannelRoot,
    [string]$ChannelUrl = 'http://192.168.0.23:8080',
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version = '0.2.2',
    [string]$VerificationFile = 'alarm-compact-verification.json',
    [string]$BuildLogName = 'alarm-compact-build.log',
    [string]$ReleaseNotes = '新增跟随事项时间与状态联动的本机闹钟，支持权限引导、关闭和稍后提醒。今天、日历、计划、记录、我的及编辑页改为紧凑布局，保留本地数据与局域网同步。小米及现代 Android 闹钟场景仍待实机体验。'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$artifactRoot = Join-Path $projectRoot 'artifacts'
$apkName = "try-to-batter-$Version-preview.apk"
$buildApk = Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$testLog = Join-Path $artifactRoot 'logs\connection-instrumentation.log'
$buildLog = Join-Path $artifactRoot ('logs\' + $BuildLogName)
if (-not (Test-Path -LiteralPath $buildApk)) { throw 'Build APK missing' }
if (-not ((Get-Content -LiteralPath $buildLog -Raw) -match 'BUILD SUCCESSFUL')) { throw 'Build/lint/unit verification has not passed' }
$channelPath = (Resolve-Path -LiteralPath $ChannelRoot).Path
$catalogPath = Join-Path $channelPath 'android-update.json'
$catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
$beforeUpdates = $catalog.updates | ConvertTo-Json -Depth 20 -Compress
$sha = (Get-FileHash -LiteralPath $buildApk -Algorithm SHA256).Hash.ToLowerInvariant()
$verified = Get-Content -LiteralPath (Join-Path $artifactRoot $VerificationFile) -Raw | ConvertFrom-Json
$basicChecks = $verified.verificationScope -eq 'user_requested_basic_checks' -and $verified.lintStatus -eq 'not_run_user_requested'
if ($verified.apkSha256 -ne $sha -or $verified.versionName -ne $Version -or -not $verified.buildPassed -or -not $verified.signatureVerified -or (-not $basicChecks -and $verified.lintErrors -ne 0)) { throw 'This exact APK has not passed build/signature verification' }
if ($basicChecks -and (-not $verified.apkManifestVerified -or -not $verified.dataSchemaUnchanged -or -not $verified.permissionsUnchanged)) { throw 'Basic delivery compatibility checks missing' }
if (-not $verified.fullRegressionPassed) {
    if ($verified.verificationScope -eq 'affected_features_and_core_dependencies') {
        if (@($verified.unitTests).Count -eq 0 -or @($verified.unitTests | Where-Object { $_.failures -gt 0 -or $_.errors -gt 0 -or $_.tests -lt 1 }).Count -gt 0) { throw 'Affected unit verification has not passed' }
        $checks = @($verified.instrumentation.PSObject.Properties)
        if ($checks.Count -eq 0) { throw 'Affected instrumentation evidence missing' }
        foreach ($check in $checks) {
            if ($check.Name -notmatch '^[\w.-]+\.log$' -or [int]$check.Value -lt 1) { throw 'Invalid instrumentation evidence entry' }
            $checkLog = Get-Content -LiteralPath (Join-Path $artifactRoot ('logs\' + $check.Name)) -Raw
            if ($checkLog -notmatch ('OK \(' + [int]$check.Value + ' tests?\)')) { throw ('Instrumentation evidence has not passed: ' + $check.Name) }
        }
    } elseif ($verified.verificationScope -ne 'user_requested_basic_checks') { throw 'Partial verification requires the user-requested delivery scope' }
}
$publishedApk = Join-Path $channelPath $apkName
if ((Test-Path -LiteralPath $publishedApk) -and ((Get-FileHash -LiteralPath $publishedApk).Hash.ToLowerInvariant() -ne $sha)) { throw 'A different APK already exists at this version; choose a new version' }
Copy-Item -LiteralPath $buildApk -Destination (Join-Path $artifactRoot $apkName)
Copy-Item -LiteralPath $buildApk -Destination $publishedApk
Copy-Item -LiteralPath $catalogPath -Destination (Join-Path $artifactRoot "kanjian-update-catalog-before-$Version.json")
$catalog.publicApks = @($catalog.publicApks) + @($apkName) | Select-Object -Unique
if (($catalog.updates | ConvertTo-Json -Depth 20 -Compress) -ne $beforeUpdates) { throw 'Existing update entries changed' }
$tempCatalogPath = Join-Path $channelPath 'android-update.json.batter-temp'
[IO.File]::WriteAllText($tempCatalogPath, (($catalog | ConvertTo-Json -Depth 20) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $tempCatalogPath -Destination $catalogPath -Force
$downloadUrl = $ChannelUrl.TrimEnd('/') + '/' + $apkName
$downloadPath = Join-Path $artifactRoot "download-verification-$Version.apk"
Add-Type -AssemblyName System.Net.Http
$downloadHandler = [System.Net.Http.HttpClientHandler]::new()
$downloadHandler.UseProxy = $false
$downloadClient = [System.Net.Http.HttpClient]::new($downloadHandler)
$downloadClient.Timeout = [TimeSpan]::FromSeconds(30)
try {
    $response = $downloadClient.GetAsync($downloadUrl).GetAwaiter().GetResult()
    $response.EnsureSuccessStatusCode() | Out-Null
    [IO.File]::WriteAllBytes($downloadPath, $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
    $headRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Head, $downloadUrl)
    $head = $downloadClient.SendAsync($headRequest).GetAwaiter().GetResult()
    $head.EnsureSuccessStatusCode() | Out-Null
} finally {
    $downloadClient.Dispose()
    $downloadHandler.Dispose()
}
$downloadSha = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($downloadSha -ne $sha) { throw 'Published download checksum mismatch' }
$receipt = [ordered]@{
    version = $Version; versionCode = $verified.versionCode; packageName = 'com.trytobatter.app'; apk = $apkName
    bytes = (Get-Item -LiteralPath $publishedApk).Length; sha256 = $sha
    downloadUrl = $downloadUrl; httpStatus = [int]$head.StatusCode
    downloadSha256Matches = $true; existingKanjianUpdatesPreserved = $true
    channelRoot = $channelPath; verifiedAt = [DateTimeOffset]::Now.ToString('o')
}
[IO.File]::WriteAllText((Join-Path $artifactRoot 'distribution-receipt.json'), ($receipt | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
$release = [ordered]@{packageName='com.trytobatter.app';versionCode=$verified.versionCode;versionName=$Version;fileName=$apkName;size=$receipt.bytes;sha256=$sha;releaseNotes=$ReleaseNotes}
$releasePath = Join-Path $projectRoot 'server\release.json'
Copy-Item -LiteralPath $releasePath -Destination (Join-Path $artifactRoot "release-before-$Version.json")
$tempReleasePath = $releasePath + '.publish-temp'
[IO.File]::WriteAllText($tempReleasePath, ($release | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $tempReleasePath -Destination $releasePath -Force
$receipt | ConvertTo-Json -Depth 4
