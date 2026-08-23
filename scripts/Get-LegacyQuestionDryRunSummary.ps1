<#
.SYNOPSIS
通过 Gateway 汇总已完成历史题库 DryRun 的持久化复核结果。

.DESCRIPTION
输入是迁移脚本生成的已完成 DryRun 检查点，以及当前进程中的短期教师 Access Token。
脚本只读任务详情和分页复核 API，输出 outcome、issueCode 和问题码组合的聚合计数与比例。
不会读取历史 JSON、推进或改写检查点，也不会输出 Token、题目内容、路径、完整任务 ID 或 HTTP 响应。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CheckpointPath,
    [string]$ApiBaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(1, 100)]
    [int]$PageSize = 100
)

$ErrorActionPreference = 'Stop'
$accessToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw 'EDU_MIGRATION_ACCESS_TOKEN is required in the current process.'
}
$headers = @{ 'Authorization' = "Bearer $accessToken"; 'Accept' = 'application/json' }
. (Join-Path $PSScriptRoot 'private\LegacyMigrationHttpDiagnostics.ps1')

# 输入显式检查点文件，输出去重后的任务 ID；只接受已完成 DryRun，不回显路径或文件正文。
function Read-DryRunTaskIds([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'DryRun checkpoint was not found; its path was suppressed.'
    }
    try {
        $text = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
        if ($text.Length -gt 4 * 1024 * 1024) { throw 'CHECKPOINT_TOO_LARGE' }
        $checkpoint = $text | ConvertFrom-Json
    } catch {
        throw 'DryRun checkpoint is invalid; its path and contents were suppressed.'
    }
    if ([string]$checkpoint.action -ne 'DryRun' -or -not [bool]$checkpoint.completed -or
            $null -ne $checkpoint.activeTask) {
        throw 'Summary requires a completed DryRun checkpoint with no active task.'
    }

    $ids = New-Object System.Collections.Generic.List[string]
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($summary in @($checkpoint.taskSummaries)) {
        $candidate = [string]$summary.importTaskId
        $parsed = [Guid]::Empty
        if (-not [Guid]::TryParse($candidate, [ref]$parsed) -or -not $seen.Add($candidate)) {
            throw 'DryRun checkpoint contains an invalid or duplicate task reference.'
        }
        $ids.Add($candidate)
    }
    if ($ids.Count -eq 0 -and [int64]$checkpoint.totalItems -ne 0) {
        throw 'DryRun checkpoint contains no task references for its declared items.'
    }
    return $ids.ToArray()
}

# 输入 Gateway GET 地址，输出解析后的 JSON；HTTP 失败只抛出现有白名单诊断，不回显响应正文。
function Invoke-SafeSummaryGet([string]$Uri) {
    try {
        return Invoke-RestMethod -Method Get -Uri $Uri -Headers $headers
    } catch {
        $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $_ -SensitiveValues @($accessToken)
        throw ($diagnostic | ConvertTo-Json -Depth 4 -Compress)
    }
}

# 输入计数与总记录数，输出四位小数百分比；空集合统一返回 0。
function Get-SummaryPercentage([int64]$Count, [int64]$Total) {
    if ($Total -eq 0) { return [double]0 }
    return [Math]::Round(($Count * 100.0) / $Total, 4, [MidpointRounding]::AwayFromZero)
}

# 输入 API 数值字段，输出非负 Int64；异常只报告字段类别，不回显响应或任务标识。
function ConvertTo-NonNegativeInt64([object]$Value, [string]$FieldName) {
    $parsed = [int64]0
    if ($null -eq $Value -or -not [int64]::TryParse([string]$Value, [ref]$parsed) -or $parsed -lt 0) {
        throw "Summary API returned an invalid $FieldName value."
    }
    return $parsed
}

$baseUri = $null
if (-not [Uri]::TryCreate($ApiBaseUrl, [UriKind]::Absolute, [ref]$baseUri) -or
        @('http', 'https') -notcontains $baseUri.Scheme -or
        -not [string]::IsNullOrWhiteSpace($baseUri.Query) -or
        -not [string]::IsNullOrWhiteSpace($baseUri.Fragment)) {
    throw 'ApiBaseUrl must be an absolute HTTP(S) Gateway URL without query or fragment.'
}
$apiRoot = $ApiBaseUrl.TrimEnd('/')
$taskIds = @(Read-DryRunTaskIds $CheckpointPath)
$allowedOutcomes = @('VALID', 'NEEDS_REVIEW', 'FAILED', 'SKIPPED')
$terminalStatuses = @('COMPLETED', 'PARTIAL_SUCCESS')
$outcomeCounts = [ordered]@{ VALID = [int64]0; NEEDS_REVIEW = [int64]0; FAILED = [int64]0; SKIPPED = [int64]0 }
$issueCounts = @{}
$combinationCounts = @{}
$seenItems = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$totalRecords = [int64]0

foreach ($taskId in $taskIds) {
    $safeTaskId = [Uri]::EscapeDataString($taskId)
    $task = Invoke-SafeSummaryGet "$apiRoot/api/v1/teacher/question-imports/$safeTaskId"
    if ([string]$task.id -ne $taskId -or [string]$task.sourceFormat -ne 'LEGACY_JSON' -or
            $task.dryRun -ne $true -or $terminalStatuses -notcontains [string]$task.status) {
        throw 'Checkpoint task is not an owned terminal LEGACY_JSON DryRun task.'
    }
    $expectedTaskRecords = ConvertTo-NonNegativeInt64 $task.processedRows 'processedRows'
    $taskRecords = [int64]0
    $expectedPageTotal = $null
    $page = 1

    while ($true) {
        $pageResult = Invoke-SafeSummaryGet "$apiRoot/api/v1/teacher/question-imports/$safeTaskId/items?page=$page&size=$PageSize"
        $pageTotal = ConvertTo-NonNegativeInt64 $pageResult.total 'page total'
        if ($null -eq $expectedPageTotal) { $expectedPageTotal = $pageTotal }
        elseif ([int64]$expectedPageTotal -ne $pageTotal) { throw 'Summary API page total changed during the read.' }
        if ([int]$pageResult.page -ne $page -or [int]$pageResult.size -ne $PageSize) {
            throw 'Summary API returned inconsistent pagination metadata.'
        }

        $items = @($pageResult.items)
        if ($items.Count -gt $PageSize) { throw 'Summary API returned more items than the requested page size.' }
        foreach ($item in $items) {
            $itemId = [string]$item.id
            $parsedItemId = [Guid]::Empty
            if (-not [Guid]::TryParse($itemId, [ref]$parsedItemId) -or -not $seenItems.Add($itemId)) {
                throw 'Summary API returned an invalid or duplicate item reference.'
            }
            $outcome = [string]$item.outcome
            if ($allowedOutcomes -notcontains $outcome) {
                throw 'DryRun summary received an unsupported or non-DryRun outcome.'
            }
            $outcomeCounts[$outcome] = [int64]$outcomeCounts[$outcome] + 1
            $taskRecords++
            $totalRecords++

            $itemIssueCodes = New-Object System.Collections.Generic.List[string]
            $seenItemIssueCodes = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
            foreach ($issue in @($item.issues)) {
                $code = [string]$issue.code
                if ($code -notmatch '^[A-Z][A-Z0-9_]{0,127}$' -or -not $seenItemIssueCodes.Add($code)) {
                    throw 'Summary API returned an invalid or duplicate issueCode.'
                }
                $itemIssueCodes.Add($code)
                if (-not $issueCounts.ContainsKey($code)) { $issueCounts[$code] = [int64]0 }
                $issueCounts[$code] = [int64]$issueCounts[$code] + 1
            }
            if ($itemIssueCodes.Count -gt 0) {
                $combination = (@($itemIssueCodes.ToArray() | Sort-Object) -join '+')
                if (-not $combinationCounts.ContainsKey($combination)) { $combinationCounts[$combination] = [int64]0 }
                $combinationCounts[$combination] = [int64]$combinationCounts[$combination] + 1
            }
        }

        if ($taskRecords -ge $pageTotal) { break }
        if ($items.Count -eq 0) { throw 'Summary API pagination ended before its declared total.' }
        $page++
    }
    if ($taskRecords -ne [int64]$expectedPageTotal -or $taskRecords -ne $expectedTaskRecords) {
        throw 'DryRun task and item totals are inconsistent.'
    }
}

$outcomeSummary = foreach ($name in $allowedOutcomes) {
    $count = [int64]$outcomeCounts[$name]
    [pscustomobject][ordered]@{
        outcome = $name
        count = $count
        percentage = Get-SummaryPercentage $count $totalRecords
    }
}
$issueSummary = foreach ($entry in @($issueCounts.GetEnumerator() | Sort-Object @{ Expression = 'Value'; Descending = $true }, @{ Expression = 'Key'; Descending = $false })) {
    [pscustomobject][ordered]@{
        issueCode = [string]$entry.Key
        count = [int64]$entry.Value
        percentage = Get-SummaryPercentage ([int64]$entry.Value) $totalRecords
    }
}
$combinationSummary = foreach ($entry in @($combinationCounts.GetEnumerator() | Sort-Object @{ Expression = 'Value'; Descending = $true }, @{ Expression = 'Key'; Descending = $false })) {
    [pscustomobject][ordered]@{
        issueCodes = @([string]$entry.Key -split '\+')
        count = [int64]$entry.Value
        percentage = Get-SummaryPercentage ([int64]$entry.Value) $totalRecords
    }
}

[pscustomobject][ordered]@{
    totalTaskCount = $taskIds.Count
    totalRecordCount = $totalRecords
    outcomes = @($outcomeSummary)
    issueCodes = @($issueSummary)
    issueCodeCombinations = @($combinationSummary)
} | ConvertTo-Json -Depth 8 -Compress
