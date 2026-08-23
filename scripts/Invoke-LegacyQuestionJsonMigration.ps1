<#
.SYNOPSIS
以有界任务把历史逐题 JSON 提交到题库导入接口。

.DESCRIPTION
输入是教师显式指定的只读目录和当前进程中的短期教师令牌；输出是任务 ID 与聚合计数。
默认最多处理 20 项。只有显式使用 AllItems，且正式导入额外确认 ConfirmFullMigration 时，才会把稳定排序后的全部文件拆成多个任务。
正式导入可引用已完成的全量 DryRun 检查点，只提交持久化结果为 VALID 的来源；图片 URL 会随题目提交并由 learning-service 调用 media-service 安全转存。
检查点只保存指纹、索引、任务 ID 和计数，不保存令牌、绝对源路径、题干、答案或原始 JSON；中断后用相同参数重跑即可续传。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('DryRun', 'Import', 'Cleanup')]
    [string]$Action,
    [string]$SourceRoot,
    [string]$ApiBaseUrl = 'http://127.0.0.1:8080',
    [string]$SourceSystem = 'LEGACY_RAWSTON_V3',
    [ValidateRange(1, 1000)]
    [int]$MaxItems = 20,
    [ValidateRange(1, 1000)]
    [int]$TaskSize = 1000,
    [ValidateRange(1, 20)]
    [int]$BatchSize = 10,
    [switch]$AllItems,
    [switch]$ConfirmFullMigration,
    [string]$CheckpointPath,
    [string]$ImportTaskId,
    [string]$ValidatedDryRunCheckpointPath,
    [string[]]$AllowedImageHost = @(),
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$accessToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
if ([string]::IsNullOrWhiteSpace($accessToken)) { throw 'EDU_MIGRATION_ACCESS_TOKEN is required in the current process.' }
$headers = @{ 'Authorization' = "Bearer $accessToken"; 'Content-Type' = 'application/json' }
. (Join-Path $PSScriptRoot 'private\LegacyMigrationHttpDiagnostics.ps1')
. (Join-Path $PSScriptRoot 'private\LegacyValidatedImportSelection.ps1')

# 输入任意来源字节，输出小写 SHA-256；用于来源与检查点幂等，不输出原文。
function Get-Sha256Hex([byte[]]$Bytes) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}

# 输入稳定运行键，输出可重放的 UUID；请求成功但响应丢失时，后端据此返回原任务或批次。
function Get-DeterministicGuid([string]$Value) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
        $bytes = New-Object byte[] 16
        [Array]::Copy($digest, $bytes, 16)
        # 标记为 RFC 4122 variant/version，输出仍由相同输入稳定决定，便于请求丢失后安全重放。
        $bytes[7] = ($bytes[7] -band 0x0f) -bor 0x50
        $bytes[8] = ($bytes[8] -band 0x3f) -bor 0x80
        return ([Guid]::new($bytes)).ToString()
    } finally { $algorithm.Dispose() }
}

# 输入不含敏感内容的内存状态，原子写入本机检查点；写失败时保留旧文件而不推进进度。
function Save-Checkpoint([object]$Checkpoint, [string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
    $temporary = "$Path.tmp"
    [IO.File]::WriteAllText($temporary, ($Checkpoint | ConvertTo-Json -Depth 12), $utf8NoBom)
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

# 输入显式检查点路径，输出恢复状态；损坏内容只报告安全错误，不回显文件正文。
function Read-Checkpoint([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try { return [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw 'Migration checkpoint is invalid; its path and contents were suppressed.' }
}

# 输入管理员明确批准的图片域名，输出去重小写域名；禁止通配符、URL、端口和路径，避免放宽 media-service 的 SSRF 边界。
function Get-NormalizedAllowedImageHosts([string[]]$Hosts) {
    $normalized = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($hostValue in @($Hosts)) {
        $approvedHostValue = [string]$hostValue
        if ([string]::IsNullOrWhiteSpace($approvedHostValue) -or $approvedHostValue.Trim() -ne $approvedHostValue -or
                $approvedHostValue.Contains('*') -or $approvedHostValue.Contains('://') -or
                $approvedHostValue.Contains('/') -or $approvedHostValue.Contains('\') -or
                $approvedHostValue.Contains(':') -or
                $approvedHostValue -notmatch '^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$') {
            throw 'AllowedImageHost must contain exact DNS host names only; values were suppressed.'
        }
        [void]$normalized.Add($approvedHostValue.ToLowerInvariant())
    }
    return @($normalized | Sort-Object)
}

# 输入单个来源文件，输出筛选所需的稳定 externalId；只有已在 DryRun 中为 VALID 的项才进一步提取图片域名。
# 源正文、图片 URL 和文件路径只在当前进程内使用，错误信息不会回显这些内容。
function Get-SourceSelectionDescriptor([string]$Path, [int]$SourceIndex, [object]$ValidatedExternalIds) {
    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
        if ($bytes.Length -gt 256 * 1024) { throw 'FILE_TOO_LARGE' }
        $json = $utf8NoBom.GetString($bytes) | ConvertFrom-Json
        if ($null -eq $json -or $json -is [Array]) { throw 'JSON_OBJECT_REQUIRED' }
        $idValue = $json.exerciseID
        if ($null -ne $idValue -and ($idValue -is [Array] -or $idValue -is [System.Management.Automation.PSCustomObject])) {
            throw 'EXTERNAL_ID_INVALID'
        }
        $externalId = [string]$idValue
        if ([string]::IsNullOrWhiteSpace($externalId)) { $externalId = [IO.Path]::GetFileNameWithoutExtension($Path) }
        if ([string]::IsNullOrWhiteSpace($externalId) -or $externalId.Trim() -ne $externalId -or $externalId.Length -gt 160) {
            throw 'EXTERNAL_ID_INVALID'
        }
        if (-not $ValidatedExternalIds.Contains($externalId)) {
            return [pscustomobject]@{ externalId = $externalId; selected = $false; imageHosts = @() }
        }

        $imageHosts = New-Object System.Collections.Generic.List[string]
        $pending = New-Object System.Collections.Generic.Stack[object]
        $pending.Push($json)
        while ($pending.Count -gt 0) {
            $node = $pending.Pop()
            foreach ($fieldName in @('question', 'solution')) {
                $value = $node.$fieldName
                if ($null -eq $value -or $value -is [Array] -or
                        $value -is [System.Management.Automation.PSCustomObject]) { continue }
                foreach ($match in [Text.RegularExpressions.Regex]::Matches(
                        [string]$value, '(?is)<img\b[^>]*\bsrc\s*=\s*([''"])(.*?)\1[^>]*>')) {
                    $source = $match.Groups[2].Value.Trim()
                    $uri = $null
                    if (-not [Uri]::TryCreate($source, [UriKind]::Absolute, [ref]$uri) -or
                            $uri.Scheme -ne 'https' -or -not [string]::IsNullOrEmpty($uri.UserInfo) -or
                            [string]::IsNullOrWhiteSpace($uri.Host) -or ($uri.Port -ne 443)) {
                        throw 'EXTERNAL_IMAGE_URL_INVALID'
                    }
                    $imageHosts.Add($uri.Host.ToLowerInvariant())
                }
            }
            foreach ($child in @($node.subQues)) {
                if ($null -ne $child -and $child -is [System.Management.Automation.PSCustomObject]) { $pending.Push($child) }
            }
        }
        return [pscustomobject]@{ externalId = $externalId; selected = $true; imageHosts = @($imageHosts) }
    } catch {
        $safeReason = if (@('FILE_TOO_LARGE', 'JSON_OBJECT_REQUIRED', 'EXTERNAL_ID_INVALID', 'EXTERNAL_IMAGE_URL_INVALID') `
                -contains [string]$_.Exception.Message) { [string]$_.Exception.Message } else { 'SOURCE_INSPECTION_FAILED' }
        throw "Legacy source item $SourceIndex could not be inspected ($safeReason); source content, image URL and path were suppressed."
    }
}

# 输入一个已创建的任务 ID，输出后端精确清理计数；不会清表或扫描其他任务。
function Invoke-TaskCleanup([string]$TaskId) {
    try {
        return Invoke-RestMethod -Method Delete -Uri "$ApiBaseUrl/api/v1/teacher/legacy-question-imports/$TaskId/created-resources" -Headers $headers
    } catch { throw "Legacy cleanup request failed for task $TaskId; response content was suppressed." }
}

if ($Action -eq 'Cleanup') {
    if (-not [string]::IsNullOrWhiteSpace($ImportTaskId)) {
        $result = Invoke-TaskCleanup $ImportTaskId
        [pscustomobject]@{
            importTaskId = $result.importTaskId
            questionsDeleted = $result.questionsDeleted
            sourceReferencesDeleted = $result.sourceReferencesDeleted
            mediaReferencesReleased = $result.mediaReferencesReleased
            alreadyCleaned = $result.alreadyCleaned
        } | ConvertTo-Json -Compress
        exit 0
    }
    if ([string]::IsNullOrWhiteSpace($CheckpointPath)) { throw 'Cleanup requires ImportTaskId or an explicit CheckpointPath.' }
    $cleanupCheckpoint = Read-Checkpoint $CheckpointPath
    if ($null -eq $cleanupCheckpoint -or $cleanupCheckpoint.action -ne 'Import') {
        throw 'Cleanup checkpoint must belong to an Import run.'
    }
    $taskIds = @($cleanupCheckpoint.taskSummaries | ForEach-Object { $_.importTaskId })
    if ($null -ne $cleanupCheckpoint.activeTask -and -not [string]::IsNullOrWhiteSpace([string]$cleanupCheckpoint.activeTask.importTaskId)) {
        $taskIds += [string]$cleanupCheckpoint.activeTask.importTaskId
    }
    $taskIds = @($taskIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    if ($taskIds.Count -eq 0) { throw 'Cleanup checkpoint contains no created import task.' }
    $results = foreach ($taskId in $taskIds) {
        $result = Invoke-TaskCleanup $taskId
        [pscustomobject]@{
            importTaskId = $result.importTaskId
            questionsDeleted = $result.questionsDeleted
            sourceReferencesDeleted = $result.sourceReferencesDeleted
            mediaReferencesReleased = $result.mediaReferencesReleased
            alreadyCleaned = $result.alreadyCleaned
        }
    }
    [pscustomobject]@{ cleanedTaskCount = $results.Count; tasks = @($results) } | ConvertTo-Json -Depth 5 -Compress
    exit 0
}

if ([string]::IsNullOrWhiteSpace($SourceRoot)) { throw "$Action requires SourceRoot." }
if ($SourceSystem -notmatch '^[A-Z0-9][A-Z0-9_-]{2,79}$') { throw 'SourceSystem does not satisfy the API contract.' }
if (-not [string]::IsNullOrWhiteSpace($ValidatedDryRunCheckpointPath) -and
        ($Action -ne 'Import' -or -not $AllItems)) {
    throw 'ValidatedDryRunCheckpointPath is only valid with -Action Import -AllItems.'
}
if ($PlanOnly -and [string]::IsNullOrWhiteSpace($ValidatedDryRunCheckpointPath)) {
    throw 'PlanOnly requires ValidatedDryRunCheckpointPath.'
}
if ($AllowedImageHost.Count -gt 0 -and [string]::IsNullOrWhiteSpace($ValidatedDryRunCheckpointPath)) {
    throw 'AllowedImageHost is only valid with ValidatedDryRunCheckpointPath.'
}
if ($Action -eq 'Import' -and $AllItems -and -not $PlanOnly -and -not $ConfirmFullMigration) {
    throw 'Full Import requires both -AllItems and -ConfirmFullMigration.'
}
if ($ConfirmFullMigration -and (-not $AllItems -or $Action -ne 'Import' -or $PlanOnly)) {
    throw 'ConfirmFullMigration is only valid with -Action Import -AllItems.'
}

$resolvedRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) { throw 'SourceRoot must be a readable directory.' }
if (-not [string]::IsNullOrWhiteSpace($CheckpointPath)) {
    $checkpointFullPath = [IO.Path]::GetFullPath($CheckpointPath)
    $rootPrefix = [IO.Path]::GetFullPath($resolvedRoot).TrimEnd('\') + '\'
    if ($checkpointFullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'CheckpointPath must be outside SourceRoot so it cannot become a migration input.'
    }
}
$sourceRootName = Split-Path -Leaf $resolvedRoot
$rootLabel = $resolvedRoot.Replace('\', '/').TrimEnd('/').ToLowerInvariant()

# 只枚举文件元数据并按 ordinal 路径稳定排序；JSON 内容仍按当前任务逐个有界读取。
$orderedPaths = @()
for ($discoveryAttempt = 1; $discoveryAttempt -le 3 -and $orderedPaths.Count -eq 0; $discoveryAttempt++) {
    $orderedPaths = @(Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File -Filter '*.json' | Select-Object -ExpandProperty FullName | Sort-Object)
    if ($orderedPaths.Count -eq 0 -and $discoveryAttempt -lt 3) { Start-Sleep -Milliseconds 200 }
}
Write-Verbose "Discovered and stably ordered $($orderedPaths.Count) JSON files."
if ($orderedPaths.Count -eq 0) { throw 'No legacy JSON files were selected.' }
$rootFingerprint = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes($rootLabel))

$manifestLines = New-Object System.Collections.Generic.List[string]
foreach ($path in $orderedPaths) {
    $file = Get-Item -LiteralPath $path
    $relative = $path.Substring($resolvedRoot.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
    $manifestLines.Add("$relative|$($file.Length)|$($file.LastWriteTimeUtc.Ticks)")
}
$manifestFingerprint = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes(($manifestLines -join "`n")))
$totalSourceFiles = $orderedPaths.Count
$selectionMode = 'SOURCE_ORDER'
$validatedSelection = $null
$selectedImageCount = 0
$imageHostCounts = [ordered]@{}
$normalizedAllowedImageHosts = @(Get-NormalizedAllowedImageHosts $AllowedImageHost)

if (-not [string]::IsNullOrWhiteSpace($ValidatedDryRunCheckpointPath)) {
    $validatedSelection = Get-ValidatedDryRunSelection `
        -CheckpointPath $ValidatedDryRunCheckpointPath `
        -ApiBaseUrl $ApiBaseUrl `
        -Headers $headers `
        -AccessToken $accessToken `
        -ExpectedSourceSystem $SourceSystem `
        -ExpectedRootFingerprint $rootFingerprint `
        -ExpectedManifestFingerprint $manifestFingerprint `
        -ExpectedSourceFileCount $totalSourceFiles

    $selectedPaths = New-Object System.Collections.Generic.List[string]
    $matchedIds = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $hostCounts = @{}
    for ($sourceIndex = 0; $sourceIndex -lt $orderedPaths.Count; $sourceIndex++) {
        $descriptor = Get-SourceSelectionDescriptor $orderedPaths[$sourceIndex] $sourceIndex $validatedSelection.externalIds
        if (-not $descriptor.selected) { continue }
        if (-not $matchedIds.Add([string]$descriptor.externalId)) {
            throw 'The current source snapshot contains duplicate VALID source keys; source values and paths were suppressed.'
        }
        $selectedPaths.Add($orderedPaths[$sourceIndex])
        foreach ($imageHost in @($descriptor.imageHosts)) {
            $selectedImageCount++
            if ($hostCounts.ContainsKey($imageHost)) { $hostCounts[$imageHost]++ } else { $hostCounts[$imageHost] = 1 }
        }
    }
    if ($matchedIds.Count -ne [int]$validatedSelection.validCount) {
        throw 'The current source snapshot does not contain every persisted VALID source key.'
    }
    foreach ($entry in @($hostCounts.GetEnumerator() | Sort-Object -Property @{ Expression = 'Value'; Descending = $true }, Name)) {
        $imageHostCounts[[string]$entry.Name] = [int]$entry.Value
    }

    if ($PlanOnly) {
        [pscustomobject][ordered]@{
            action = 'ImportPlan'
            selectionMode = 'DRY_RUN_VALID_ONLY'
            dryRunTaskCount = [int]$validatedSelection.dryRunTaskCount
            totalSourceFiles = $totalSourceFiles
            selectedValidCount = $selectedPaths.Count
            selectedImageCount = $selectedImageCount
            imageHostCounts = [pscustomobject]$imageHostCounts
        } | ConvertTo-Json -Depth 5 -Compress
        exit 0
    }

    $missingHosts = @($imageHostCounts.Keys | Where-Object { $normalizedAllowedImageHosts -notcontains $_ })
    if ($missingHosts.Count -gt 0) {
        throw 'AllowedImageHost does not cover every image host in the VALID selection; run -PlanOnly and approve exact hosts before Import.'
    }
    $orderedPaths = @($selectedPaths)
    $selectionMode = 'DRY_RUN_VALID_ONLY'
}

$totalItems = if ($AllItems) { $orderedPaths.Count } else { [Math]::Min($MaxItems, $orderedPaths.Count) }
if ($totalItems -eq 0) { throw 'No source item is eligible for this migration.' }
$effectiveTaskSize = if ($AllItems) { $TaskSize } else { $totalItems }
$validatedRunFingerprint = if ($null -eq $validatedSelection) { '-' } else { [string]$validatedSelection.dryRunRunFingerprint }
$allowedHostLabel = $normalizedAllowedImageHosts -join ','
$runFingerprint = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes(
    "$Action|$SourceSystem|$rootFingerprint|$manifestFingerprint|$totalItems|$effectiveTaskSize|$BatchSize|$selectionMode|$validatedRunFingerprint|$allowedHostLabel"))

if ($AllItems -and [string]::IsNullOrWhiteSpace($CheckpointPath)) {
    $checkpointDirectory = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) 'ccnu-edulion\migration-checkpoints'
    $checkpointAction = if ($selectionMode -eq 'DRY_RUN_VALID_ONLY') { 'import-valid' } else { $Action.ToLowerInvariant() }
    $CheckpointPath = Join-Path $checkpointDirectory "$checkpointAction-$($rootFingerprint.Substring(0, 12))-$($manifestFingerprint.Substring(0, 12)).json"
}
if (-not [string]::IsNullOrWhiteSpace($CheckpointPath)) {
    $checkpointFullPath = [IO.Path]::GetFullPath($CheckpointPath)
    $rootPrefix = [IO.Path]::GetFullPath($resolvedRoot).TrimEnd('\') + '\'
    if ($checkpointFullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'CheckpointPath must be outside SourceRoot so it cannot become a migration input.'
    }
}

$checkpoint = if (-not [string]::IsNullOrWhiteSpace($CheckpointPath)) { Read-Checkpoint $CheckpointPath } else { $null }
if ($null -ne $checkpoint) {
    if ([string]$checkpoint.runFingerprint -ne $runFingerprint) {
        throw 'Existing checkpoint does not match this source manifest or migration parameters; use its original command or a new CheckpointPath.'
    }
} else {
    $checkpoint = [pscustomobject][ordered]@{
        version = 1
        runFingerprint = $runFingerprint
        action = $Action
        sourceSystem = $SourceSystem
        sourceRootFingerprint = $rootFingerprint
        sourceManifestFingerprint = $manifestFingerprint
        totalSourceFiles = $totalSourceFiles
        totalItems = $totalItems
        selectionMode = $selectionMode
        validatedDryRunFingerprint = $validatedRunFingerprint
        dryRunTaskCount = if ($null -eq $validatedSelection) { 0 } else { [int]$validatedSelection.dryRunTaskCount }
        selectedImageCount = $selectedImageCount
        imageHostCounts = [pscustomobject]$imageHostCounts
        approvedImageHosts = @($normalizedAllowedImageHosts)
        taskSize = $effectiveTaskSize
        batchSize = $BatchSize
        nextSourceIndex = 0
        activeTask = $null
        taskSummaries = @()
        totals = [pscustomobject][ordered]@{ processed = 0; imported = 0; valid = 0; review = 0; skipped = 0; failed = 0 }
        completed = $false
    }
    Save-Checkpoint $checkpoint $CheckpointPath
}

if ([bool]$checkpoint.completed) {
    $completedResult = [ordered]@{
        action = $Action
        alreadyComplete = $true
        selectionMode = if ($null -eq $checkpoint.selectionMode) { 'SOURCE_ORDER' } else { [string]$checkpoint.selectionMode }
        totalSourceFiles = if ($null -eq $checkpoint.totalSourceFiles) { [int]$checkpoint.totalItems } else { [int]$checkpoint.totalSourceFiles }
        totalItems = [int]$checkpoint.totalItems
        completedTaskCount = @($checkpoint.taskSummaries).Count
        processedCount = [int]$checkpoint.totals.processed
        importedCount = [int]$checkpoint.totals.imported
        validCount = [int]$checkpoint.totals.valid
        reviewCount = [int]$checkpoint.totals.review
        skippedCount = [int]$checkpoint.totals.skipped
        failedCount = [int]$checkpoint.totals.failed
    }
    if ([string]$checkpoint.selectionMode -eq 'DRY_RUN_VALID_ONLY') {
        $completedResult.dryRunTaskCount = [int]$checkpoint.dryRunTaskCount
        $completedResult.selectedImageCount = [int]$checkpoint.selectedImageCount
        $completedResult.imageHostCounts = $checkpoint.imageHostCounts
    } else {
        $completedResult.checkpointPath = $CheckpointPath
    }
    [pscustomobject]$completedResult | ConvertTo-Json -Depth 5 -Compress
    exit 0
}

# 输入规范化相对路径，输出教材版本和年级目录原文；兼容从总根、版本或年级层级开始的选择。
function Get-PathMetadata([string]$RelativePath) {
    $parts = @($RelativePath.Split('/'))
    $directories = if ($parts.Count -gt 1) { @($parts[0..($parts.Count - 2)]) } else { @() }
    $candidates = @($sourceRootName) + $directories
    $editionMarker = [string][char]0x7248
    $questionBankName = ([string][char]0x9898) + ([string][char]0x5e93)
    $gradeIndex = -1
    for ($index = 0; $index -lt $candidates.Count; $index++) {
        if ($candidates[$index] -match '[\u4e03\u516b\u4e5d]\u5e74\u7ea7') { $gradeIndex = $index; break }
    }
    if ($gradeIndex -ge 0) {
        $pathGrade = $candidates[$gradeIndex]
        $pathEdition = $null
        for ($index = $gradeIndex - 1; $index -ge 0; $index--) {
            if ($candidates[$index].Contains($editionMarker) -and $candidates[$index] -ne $questionBankName) { $pathEdition = $candidates[$index]; break }
        }
        if ([string]::IsNullOrWhiteSpace($pathEdition) -and $pathGrade.Contains($editionMarker)) { $pathEdition = $pathGrade }
        if ([string]::IsNullOrWhiteSpace($pathEdition)) { $pathEdition = 'UNKNOWN' }
        return [pscustomobject]@{ pathEdition = $pathEdition; pathGrade = $pathGrade }
    }
    if ($directories.Count -eq 2) {
        return [pscustomobject]@{ pathEdition = $directories[0]; pathGrade = $directories[1] }
    }
    return [pscustomobject]@{ pathEdition = 'UNKNOWN'; pathGrade = 'UNKNOWN' }
}

# 输入单个源文件和稳定序号，输出 API 批次项；任何本地错误都会停止且不泄露路径或题目内容。
function Convert-SourceFileToItem([string]$Path, [int]$SourceIndex) {
    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
        if ($bytes.Length -gt 256 * 1024) { throw 'FILE_TOO_LARGE' }
        $raw = $utf8NoBom.GetString($bytes)
        $json = $raw | ConvertFrom-Json
        if ($null -eq $json -or $json -is [Array]) { throw 'JSON_OBJECT_REQUIRED' }
        $relative = $Path.Substring($resolvedRoot.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
        if ([string]::IsNullOrWhiteSpace($relative) -or $relative.Length -gt 500 -or $relative -notlike '*.json' -or
                @($relative.Split('/') | Where-Object { $_ -eq '' -or $_ -eq '.' -or $_ -eq '..' }).Count -gt 0) {
            throw 'RELATIVE_PATH_INVALID'
        }
        $idValue = $json.exerciseID
        if ($null -ne $idValue -and ($idValue -is [Array] -or $idValue -is [System.Management.Automation.PSCustomObject])) {
            throw 'EXTERNAL_ID_INVALID'
        }
        $externalId = [string]$idValue
        if ([string]::IsNullOrWhiteSpace($externalId)) { $externalId = [IO.Path]::GetFileNameWithoutExtension($Path) }
        if ([string]::IsNullOrWhiteSpace($externalId) -or $externalId.Trim() -ne $externalId -or $externalId.Length -gt 160) {
            throw 'EXTERNAL_ID_INVALID'
        }
        $metadata = Get-PathMetadata $relative
        return [ordered]@{
            externalId = $externalId
            relativePath = $relative
            pathEdition = $metadata.pathEdition
            pathGrade = $metadata.pathGrade
            contentSha256 = Get-Sha256Hex $bytes
            json = $json
        }
    } catch {
        throw "Legacy source item $SourceIndex could not be prepared ($($_.Exception.Message)); source content and path were suppressed."
    }
}

# 输入当前有界批次，输出仅在错误字段原因中需要遮蔽的题干、答案和解析文本。
# 返回值只在内存中交给诊断函数，不写入检查点，也不单独输出。
function Get-BatchSensitiveValues([object[]]$BatchItems) {
    $values = New-Object System.Collections.Generic.List[string]
    foreach ($fixedValue in @($accessToken, $resolvedRoot, $SourceRoot)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$fixedValue)) { $values.Add([string]$fixedValue) }
    }
    $pending = New-Object System.Collections.Generic.Stack[object]
    foreach ($item in @($BatchItems)) { if ($null -ne $item.json) { $pending.Push($item.json) } }
    while ($pending.Count -gt 0) {
        $json = $pending.Pop()
        foreach ($name in @('question', 'queAns', 'solution')) {
            $value = $json.$name
            if ($null -ne $value -and $value -isnot [Array] -and
                    $value -isnot [System.Management.Automation.PSCustomObject] -and
                    -not [string]::IsNullOrWhiteSpace([string]$value)) {
                $values.Add([string]$value)
            }
        }
        foreach ($child in @($json.subQues)) {
            if ($null -ne $child -and $child -is [System.Management.Automation.PSCustomObject]) { $pending.Push($child) }
        }
    }
    return @($values | Select-Object -Unique)
}

while ([int]$checkpoint.nextSourceIndex -lt $totalItems) {
    $startIndex = [int]$checkpoint.nextSourceIndex
    $endExclusive = [Math]::Min($startIndex + $effectiveTaskSize, $totalItems)
    $items = New-Object System.Collections.Generic.List[object]
    for ($index = $startIndex; $index -lt $endExclusive; $index++) {
        $items.Add((Convert-SourceFileToItem $orderedPaths[$index] $index))
    }

    $taskOrdinal = [Math]::Floor($startIndex / $effectiveTaskSize)
    $clientRequestId = Get-DeterministicGuid "$runFingerprint|task|$taskOrdinal"
    if ($null -eq $checkpoint.activeTask) {
        $checkpoint.activeTask = [pscustomobject][ordered]@{
            ordinal = $taskOrdinal
            startIndex = $startIndex
            endExclusive = $endExclusive
            clientRequestId = $clientRequestId
            importTaskId = $null
            nextBatchIndex = 0
            totals = [pscustomobject][ordered]@{ processed = 0; imported = 0; valid = 0; review = 0; skipped = 0; failed = 0 }
        }
        Save-Checkpoint $checkpoint $CheckpointPath
    } elseif ([int]$checkpoint.activeTask.startIndex -ne $startIndex -or [int]$checkpoint.activeTask.endExclusive -ne $endExclusive -or
            [string]$checkpoint.activeTask.clientRequestId -ne $clientRequestId) {
        throw 'Checkpoint active task does not match the stable source slice.'
    }

    $createBody = [ordered]@{
        clientRequestId = $clientRequestId
        sourceSystem = $SourceSystem
        sourceRootFingerprint = $rootFingerprint
        dryRun = ($Action -eq 'DryRun')
        maxItems = $items.Count
    } | ConvertTo-Json -Compress
    # Windows PowerShell 5.1 会把字符串请求体按本机 ANSI 代码页编码；显式传 UTF-8 字节可避免中文题目被替换或形成非法 JSON。
    $createBodyBytes = $utf8NoBom.GetBytes($createBody)
    try {
        $task = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/v1/teacher/legacy-question-imports" -Headers $headers -Body $createBodyBytes
    } catch {
        $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $_ `
            -SensitiveValues @($accessToken, $resolvedRoot, $SourceRoot)
        throw ($diagnostic | ConvertTo-Json -Depth 4 -Compress)
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$checkpoint.activeTask.importTaskId) -and
            [string]$checkpoint.activeTask.importTaskId -ne [string]$task.id) {
        throw 'Idempotent task replay returned a different task ID.'
    }
    $checkpoint.activeTask.importTaskId = [string]$task.id
    Save-Checkpoint $checkpoint $CheckpointPath

    $batchCount = [Math]::Ceiling($items.Count / [double]$BatchSize)
    for ($batchIndex = [int]$checkpoint.activeTask.nextBatchIndex; $batchIndex -lt $batchCount; $batchIndex++) {
        $offset = $batchIndex * $BatchSize
        $count = [Math]::Min($BatchSize, $items.Count - $offset)
        $batchItems = @($items.GetRange($offset, $count))
        $batchId = Get-DeterministicGuid "$runFingerprint|task|$taskOrdinal|batch|$batchIndex"
        $body = [ordered]@{
            batchId = $batchId
            finalBatch = ($batchIndex -eq $batchCount - 1)
            items = $batchItems
        } | ConvertTo-Json -Depth 30 -Compress
        $bodyBytes = $utf8NoBom.GetBytes($body)
        try {
            $response = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/v1/teacher/legacy-question-imports/$($task.id)/batches" -Headers $headers -Body $bodyBytes
        } catch {
            $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $_ `
                -SensitiveValues (Get-BatchSensitiveValues $batchItems)
            throw ($diagnostic | ConvertTo-Json -Depth 4 -Compress)
        }
        $checkpoint.activeTask.totals.processed += [int]$response.processedCount
        $checkpoint.activeTask.totals.imported += [int]$response.importedCount
        $checkpoint.activeTask.totals.valid += [int]$response.validCount
        $checkpoint.activeTask.totals.review += [int]$response.reviewCount
        $checkpoint.activeTask.totals.skipped += [int]$response.skippedCount
        $checkpoint.activeTask.totals.failed += [int]$response.failedCount
        $checkpoint.activeTask.nextBatchIndex = $batchIndex + 1
        Save-Checkpoint $checkpoint $CheckpointPath
        Write-Progress -Activity "Legacy JSON $Action" -Status "task $($taskOrdinal + 1), batch $($batchIndex + 1)/$batchCount" `
            -PercentComplete ([Math]::Floor((($startIndex + $offset + $count) / [double]$totalItems) * 100))
    }

    $summary = [pscustomobject][ordered]@{
        ordinal = $taskOrdinal
        importTaskId = [string]$checkpoint.activeTask.importTaskId
        sourceStartIndex = $startIndex
        sourceEndExclusive = $endExclusive
        processedCount = [int]$checkpoint.activeTask.totals.processed
        importedCount = [int]$checkpoint.activeTask.totals.imported
        validCount = [int]$checkpoint.activeTask.totals.valid
        reviewCount = [int]$checkpoint.activeTask.totals.review
        skippedCount = [int]$checkpoint.activeTask.totals.skipped
        failedCount = [int]$checkpoint.activeTask.totals.failed
    }
    $checkpoint.taskSummaries = @($checkpoint.taskSummaries) + $summary
    $checkpoint.totals.processed += $summary.processedCount
    $checkpoint.totals.imported += $summary.importedCount
    $checkpoint.totals.valid += $summary.validCount
    $checkpoint.totals.review += $summary.reviewCount
    $checkpoint.totals.skipped += $summary.skippedCount
    $checkpoint.totals.failed += $summary.failedCount
    $checkpoint.nextSourceIndex = $endExclusive
    $checkpoint.activeTask = $null
    Save-Checkpoint $checkpoint $CheckpointPath
}

Write-Progress -Activity "Legacy JSON $Action" -Completed
$checkpoint.completed = $true
Save-Checkpoint $checkpoint $CheckpointPath

$finalResult = [ordered]@{
    action = $Action
    alreadyComplete = $false
    selectionMode = $selectionMode
    totalSourceFiles = $totalSourceFiles
    totalItems = $totalItems
    completedTaskCount = @($checkpoint.taskSummaries).Count
    processedCount = [int]$checkpoint.totals.processed
    importedCount = [int]$checkpoint.totals.imported
    validCount = [int]$checkpoint.totals.valid
    reviewCount = [int]$checkpoint.totals.review
    skippedCount = [int]$checkpoint.totals.skipped
    failedCount = [int]$checkpoint.totals.failed
}
if ($selectionMode -eq 'DRY_RUN_VALID_ONLY') {
    $finalResult.dryRunTaskCount = [int]$validatedSelection.dryRunTaskCount
    $finalResult.selectedImageCount = $selectedImageCount
    $finalResult.imageHostCounts = [pscustomobject]$imageHostCounts
} else {
    $finalResult.importTaskIds = @($checkpoint.taskSummaries | ForEach-Object { $_.importTaskId })
    $finalResult.checkpointPath = $CheckpointPath
}
[pscustomobject]$finalResult | ConvertTo-Json -Depth 5 -Compress
