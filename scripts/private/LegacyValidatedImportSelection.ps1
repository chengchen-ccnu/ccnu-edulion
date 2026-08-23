# 输入已完成 DryRun 检查点和当前来源指纹，经 Gateway 只读取得全部 VALID externalId。
# 返回值仅供迁移进程内筛选来源；不输出任务、条目 ID、题目内容、路径或完整 HTTP 响应。
function Get-ValidatedDryRunSelection {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$CheckpointPath,
        [Parameter(Mandatory = $true)]
        [string]$ApiBaseUrl,
        [Parameter(Mandatory = $true)]
        [hashtable]$Headers,
        [Parameter(Mandatory = $true)]
        [string]$AccessToken,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedSourceSystem,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedRootFingerprint,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedManifestFingerprint,
        [Parameter(Mandatory = $true)]
        [int]$ExpectedSourceFileCount
    )

    $checkpoint = Read-Checkpoint $CheckpointPath
    if ($null -eq $checkpoint -or [string]$checkpoint.action -ne 'DryRun' -or
            -not [bool]$checkpoint.completed -or $null -ne $checkpoint.activeTask) {
        throw 'Validated import requires a completed DryRun checkpoint with no active task.'
    }
    if ([string]$checkpoint.sourceSystem -ne $ExpectedSourceSystem -or
            [string]$checkpoint.sourceRootFingerprint -ne $ExpectedRootFingerprint -or
            [string]$checkpoint.sourceManifestFingerprint -ne $ExpectedManifestFingerprint -or
            [int]$checkpoint.totalSourceFiles -ne $ExpectedSourceFileCount -or
            [int]$checkpoint.totalItems -ne $ExpectedSourceFileCount) {
        throw 'DryRun checkpoint does not match the current source snapshot or SourceSystem.'
    }

    $taskIds = New-Object System.Collections.Generic.List[string]
    $seenTaskIds = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($summary in @($checkpoint.taskSummaries)) {
        $taskId = [string]$summary.importTaskId
        $parsedTaskId = [Guid]::Empty
        if (-not [Guid]::TryParse($taskId, [ref]$parsedTaskId) -or -not $seenTaskIds.Add($taskId)) {
            throw 'DryRun checkpoint contains an invalid or duplicate task reference.'
        }
        $taskIds.Add($taskId)
    }
    if ($taskIds.Count -eq 0) { throw 'DryRun checkpoint contains no completed task.' }

    $validIds = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $totalProcessed = [int64]0
    $totalValid = [int64]0
    $apiRoot = $ApiBaseUrl.TrimEnd('/')
    $sensitiveValues = @($AccessToken, $CheckpointPath) + @($taskIds.ToArray())
    foreach ($taskId in $taskIds) {
        $safeTaskId = [Uri]::EscapeDataString($taskId)
        try {
            $task = Invoke-RestMethod -Method Get -Uri "$apiRoot/api/v1/admin/question-imports/$safeTaskId" -Headers $Headers
        } catch {
            $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $_ -SensitiveValues $sensitiveValues
            throw ($diagnostic | ConvertTo-Json -Depth 4 -Compress)
        }
        if ([string]$task.id -ne $taskId -or [string]$task.sourceFormat -ne 'LEGACY_JSON' -or
                [string]$task.sourceSystem -ne $ExpectedSourceSystem -or [string]$task.questionScope -ne 'SYSTEM' -or $task.dryRun -ne $true -or
                @('COMPLETED', 'PARTIAL_SUCCESS') -notcontains [string]$task.status) {
            throw 'DryRun checkpoint references a task that is not an owned terminal LEGACY_JSON DryRun.'
        }
        $totalProcessed += [int64]$task.processedRows

        $page = 1
        $taskValid = [int64]0
        $expectedPageTotal = $null
        while ($true) {
            try {
                $pageResult = Invoke-RestMethod -Method Get `
                    -Uri "$apiRoot/api/v1/admin/question-imports/$safeTaskId/items?page=$page&size=100&outcome=VALID" `
                    -Headers $Headers
            } catch {
                $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $_ -SensitiveValues $sensitiveValues
                throw ($diagnostic | ConvertTo-Json -Depth 4 -Compress)
            }
            $pageTotal = [int64]$pageResult.total
            if ($pageTotal -lt 0 -or [int]$pageResult.page -ne $page -or [int]$pageResult.size -ne 100) {
                throw 'DryRun VALID query returned invalid pagination metadata.'
            }
            if ($null -eq $expectedPageTotal) { $expectedPageTotal = $pageTotal }
            elseif ([int64]$expectedPageTotal -ne $pageTotal) { throw 'DryRun VALID total changed during selection.' }
            $items = @($pageResult.items)
            if ($items.Count -gt 100) { throw 'DryRun VALID query exceeded the requested page size.' }
            foreach ($item in $items) {
                $externalId = [string]$item.externalId
                if ([string]$item.sourceSystem -ne $ExpectedSourceSystem -or
                        [string]$item.outcome -ne 'VALID' -or @($item.issues).Count -ne 0 -or
                        [string]::IsNullOrWhiteSpace($externalId) -or $externalId.Trim() -ne $externalId -or
                        $externalId.Length -gt 160 -or -not $validIds.Add($externalId)) {
                    throw 'DryRun VALID projection contains an invalid or duplicate safe source key.'
                }
                $taskValid++
                $totalValid++
            }
            if ($taskValid -ge $pageTotal) { break }
            if ($items.Count -eq 0) { throw 'DryRun VALID pagination ended before its declared total.' }
            $page++
        }
        if ($taskValid -ne [int64]$expectedPageTotal) { throw 'DryRun VALID page count is inconsistent.' }
    }

    if ($totalProcessed -ne [int64]$checkpoint.totals.processed -or
            $totalValid -ne [int64]$checkpoint.totals.valid -or
            $totalValid -ne $validIds.Count) {
        throw 'DryRun checkpoint totals and persisted VALID projections are inconsistent.'
    }
    return [pscustomobject][ordered]@{
        externalIds = $validIds
        validCount = $validIds.Count
        dryRunTaskCount = $taskIds.Count
        dryRunRunFingerprint = [string]$checkpoint.runFingerprint
    }
}
