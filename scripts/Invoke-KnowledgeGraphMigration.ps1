[CmdletBinding()]
param(
    [ValidateSet('DryRun', 'Import')][string]$Action = 'DryRun',
    [Parameter(Mandatory = $true)][string]$NodeFile,
    [Parameter(Mandatory = $true)][string]$RelationFile,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Z0-9][A-Z0-9_-]{2,79}$')][string]$SourceSystem,
    [string]$ApiBaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 500)][int]$BatchSize = 200,
    [Parameter(Mandatory = $true)][string]$CheckpointPath,
    [switch]$ConfirmImport,
    [switch]$ConfirmStageMapping,
    [switch]$ConfirmStatusMapping,
    [switch]$ConfirmTypeNames,
    [switch]$ConfirmParentKeySemantics
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'private\LegacyMigrationHttpDiagnostics.ps1')

function Get-Sha256Text([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function New-DeterministicUuid([string]$Seed) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Seed)) } finally { $sha.Dispose() }
    $value = New-Object byte[] 16; [Array]::Copy($bytes, $value, 16)
    $value[6] = ($value[6] -band 0x0f) -bor 0x50; $value[8] = ($value[8] -band 0x3f) -bor 0x80
    return (New-Object Guid -ArgumentList (,$value)).ToString()
}

function ConvertTo-Utf8JsonBytes($Value) {
    $json = $Value | ConvertTo-Json -Depth 30 -Compress
    return (New-Object Text.UTF8Encoding($false)).GetBytes($json)
}

function Invoke-SafeJsonRequest([string]$Method, [string]$Uri, $Body) {
    $headers = @{ Authorization = "Bearer $($env:EDU_MIGRATION_ACCESS_TOKEN)" }
    $requestStream = $null
    try {
        if ($null -eq $Body) { return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -UseBasicParsing }
        # PowerShell 会把直接传入的 byte[] 展开为十进制数字文本；MemoryStream 才会原样写出 UTF-8 JSON 字节。
        $requestBytes = ConvertTo-Utf8JsonBytes $Body
        $requestStream = New-Object IO.MemoryStream -ArgumentList (,$requestBytes)
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $requestStream -UseBasicParsing
    } catch {
        # 共享诊断器按可选错误字段工作；调用期间关闭 StrictMode，返回后立即恢复。
        Set-StrictMode -Off
        try {
            $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $_ `
                -SensitiveValues @([string]$env:EDU_MIGRATION_ACCESS_TOKEN)
        } finally { Set-StrictMode -Version Latest }
        $status = $diagnostic.httpStatus
        if ($status -eq 401) { throw 'Gateway returned HTTP 401; refresh EDU_MIGRATION_ACCESS_TOKEN and retry the same checkpoint.' }
        if ($status) {
            $safeCode = if ([string]::IsNullOrWhiteSpace([string]$diagnostic.code)) { 'UNKNOWN' } else { [string]$diagnostic.code }
            $safeFields = @($diagnostic.fieldErrors | ForEach-Object { "{0}: {1}" -f $_.field, $_.reason }) -join '; '
            if ([string]::IsNullOrWhiteSpace($safeFields)) { $safeFields = 'none' }
            throw "Gateway returned HTTP $status ($safeCode); safe field errors: $safeFields. Response body was suppressed."
        }
        throw 'Gateway request failed before a safe HTTP status was available.'
    } finally {
        if ($null -ne $requestStream) { $requestStream.Dispose() }
    }
}

function Save-Checkpoint($Value, [string]$Path) {
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { New-Item -ItemType Directory -Path $directory | Out-Null }
    $temporary = Join-Path $directory ((Split-Path -Leaf $Path) + '.tmp')
    $bytes = ConvertTo-Utf8JsonBytes $Value
    [IO.File]::WriteAllBytes($temporary, $bytes)
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Read-SourceData {
    $nodeDocument = Get-Content -Raw -LiteralPath $NodeFile | ConvertFrom-Json
    $nodeWrappers = @($nodeDocument.mathNodes)
    $relationWrappers = @(Get-Content -Raw -LiteralPath $RelationFile | ConvertFrom-Json)
    $nodes = New-Object System.Collections.ArrayList
    foreach ($wrapper in $nodeWrappers) {
        $p = $wrapper.properties
        $colour = $null; if ($null -ne $p.knowTypeColour) { $colour = [string]$p.knowTypeColour }
        $parentKey = $null; if ($null -ne $p.parentId) { $parentKey = [string]$p.parentId }
        $nodeHashInput = $p | Select-Object knowId,knowName,discipline,eduStage,knowTypeId,knowTypeColour,lvlSortIndex,nodeKind,parentId,status | ConvertTo-Json -Compress
        $node = New-Object PSObject -Property ([ordered]@{
            sourceKnowId = [string]$p.knowId
            name = [string]$p.knowName
            disciplineCode = [string]$p.discipline
            educationStageCode = [string]$p.eduStage
            knowledgeTypeId = [string]$p.knowTypeId
            knowledgeTypeColour = $colour
            levelSortIndex = [int]$p.lvlSortIndex
            nodeKind = [string]$p.nodeKind
            sourceParentKey = $parentKey
            sourceStatus = [string]$p.status
            contentSha256 = (Get-Sha256Text $nodeHashInput)
        })
        [void]$nodes.Add($node)
    }
    # 文件语义明确“b 是 a 的先序知识点”，因此业务方向必须反转为 b -> a。
    $relations = New-Object System.Collections.ArrayList
    foreach ($wrapper in $relationWrappers) {
        $prerequisite = [string]$wrapper.b.properties.knowId; $dependent = [string]$wrapper.a.properties.knowId; $relationType = [string]$wrapper.r.type
        $relation = New-Object PSObject -Property ([ordered]@{
            prerequisiteSourceKnowId = $prerequisite
            dependentSourceKnowId = $dependent
            sourceRelationType = $relationType
            contentSha256 = (Get-Sha256Text ($prerequisite + "`0" + $dependent + "`0" + $relationType))
        })
        [void]$relations.Add($relation)
    }
    $orderedNodes = @($nodes | Sort-Object sourceKnowId)
    $orderedRelations = @($relations | Sort-Object prerequisiteSourceKnowId,dependentSourceKnowId)
    return [pscustomobject]@{ Nodes = $orderedNodes; Relations = $orderedRelations }
}

function Get-GraphSummary($Data) {
    $nodeById = @{}; $duplicateIds = 0; $nameCounts = @{}; $stageCounts = @{}; $typeCounts = @{}; $invalidNodes = 0
    foreach ($node in $Data.Nodes) {
        if ([string]::IsNullOrWhiteSpace($node.sourceKnowId) -or [string]::IsNullOrWhiteSpace($node.name) -or
            [string]::IsNullOrWhiteSpace($node.disciplineCode) -or [string]::IsNullOrWhiteSpace($node.educationStageCode) -or
            [string]::IsNullOrWhiteSpace($node.knowledgeTypeId) -or [string]::IsNullOrWhiteSpace($node.nodeKind)) { $invalidNodes++ }
        if ($nodeById.ContainsKey($node.sourceKnowId)) { $duplicateIds++ } else { $nodeById[$node.sourceKnowId] = $node }
        $nameCounts[$node.name] = 1 + [int]$nameCounts[$node.name]
        $stageCounts[$node.educationStageCode] = 1 + [int]$stageCounts[$node.educationStageCode]
        $typeCounts[$node.knowledgeTypeId] = 1 + [int]$typeCounts[$node.knowledgeTypeId]
    }
    $out = @{}; $in = @{}; $edgeKeys = @{}; $missing = 0; $duplicates = 0; $selfLoops = 0; $crossDiscipline = 0; $crossStage = 0
    foreach ($id in $nodeById.Keys) { $out[$id] = New-Object Collections.Generic.List[string]; $in[$id] = 0 }
    foreach ($edge in $Data.Relations) {
        $key = $edge.prerequisiteSourceKnowId + "`0" + $edge.dependentSourceKnowId
        if ($edgeKeys.ContainsKey($key)) { $duplicates++; continue }; $edgeKeys[$key] = $true
        if (-not $nodeById.ContainsKey($edge.prerequisiteSourceKnowId) -or -not $nodeById.ContainsKey($edge.dependentSourceKnowId)) { $missing++; continue }
        if ($edge.prerequisiteSourceKnowId -eq $edge.dependentSourceKnowId) { $selfLoops++; continue }
        if ($nodeById[$edge.prerequisiteSourceKnowId].disciplineCode -ne $nodeById[$edge.dependentSourceKnowId].disciplineCode) { $crossDiscipline++; continue }
        if ($nodeById[$edge.prerequisiteSourceKnowId].educationStageCode -ne $nodeById[$edge.dependentSourceKnowId].educationStageCode) { $crossStage++; continue }
        $out[$edge.prerequisiteSourceKnowId].Add($edge.dependentSourceKnowId); $in[$edge.dependentSourceKnowId] = 1 + [int]$in[$edge.dependentSourceKnowId]
    }
    $queue = New-Object Collections.Generic.Queue[string]; $distance = @{}
    foreach ($id in ($nodeById.Keys | Sort-Object)) { if ($in[$id] -eq 0) { $queue.Enqueue($id); $distance[$id] = 0 } }
    $visited = 0; $longest = 0
    while ($queue.Count -gt 0) {
        $current = $queue.Dequeue(); $visited++; $longest = [Math]::Max($longest, [int]$distance[$current])
        foreach ($next in $out[$current]) { $distance[$next] = [Math]::Max([int]$distance[$next], [int]$distance[$current] + 1); $in[$next]--; if ($in[$next] -eq 0) { $queue.Enqueue($next) } }
    }
    $connected = New-Object Collections.Generic.HashSet[string]
    foreach ($edge in $Data.Relations) { [void]$connected.Add($edge.prerequisiteSourceKnowId); [void]$connected.Add($edge.dependentSourceKnowId) }
    $unresolvedParents = @($Data.Nodes | Where-Object { $_.sourceParentKey -and -not $nodeById.ContainsKey($_.sourceParentKey) } | Select-Object -ExpandProperty sourceParentKey -Unique).Count
    return [ordered]@{
        nodeCount = $Data.Nodes.Count; relationCount = $Data.Relations.Count
        stageDistribution = [ordered]@{}; typeDistribution = [ordered]@{}
        invalidNodeCount = $invalidNodes; duplicateKnowIdCount = $duplicateIds
        duplicateNameCount = @($nameCounts.GetEnumerator() | Where-Object Value -gt 1).Count
        missingEndpointCount = $missing; duplicateEdgeCount = $duplicates; selfLoopCount = $selfLoops
        crossDisciplineEdgeCount = $crossDiscipline; crossStageEdgeCount = $crossStage; cycleDetected = ($visited -ne $nodeById.Count)
        isolatedNodeCount = $nodeById.Count - $connected.Count; sourceParentUnresolvedCount = $unresolvedParents
        maxInDegree = if ($in.Count) { [int](($Data.Relations | Group-Object dependentSourceKnowId | Measure-Object Count -Maximum).Maximum) } else { 0 }
        maxOutDegree = if ($out.Count) { [int](($out.Values | ForEach-Object Count | Measure-Object -Maximum).Maximum) } else { 0 }
        longestPrerequisiteChain = if ($visited -eq $nodeById.Count) { $longest } else { 0 }
    }, $stageCounts, $typeCounts
}

if (-not (Test-Path -LiteralPath $NodeFile -PathType Leaf) -or -not (Test-Path -LiteralPath $RelationFile -PathType Leaf)) { throw 'Both read-only source files must exist.' }
$nodePath = [IO.Path]::GetFullPath($NodeFile); $relationPath = [IO.Path]::GetFullPath($RelationFile); $checkpointFull = [IO.Path]::GetFullPath($CheckpointPath)
$nodeDirectory = (Split-Path -Parent $nodePath).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$relationDirectory = (Split-Path -Parent $relationPath).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if ($checkpointFull.StartsWith($nodeDirectory, [StringComparison]::OrdinalIgnoreCase) -or $checkpointFull.StartsWith($relationDirectory, [StringComparison]::OrdinalIgnoreCase)) { throw 'CheckpointPath must be outside both read-only source directories.' }
if ($Action -eq 'Import' -and (-not $ConfirmImport -or -not $ConfirmStageMapping -or -not $ConfirmStatusMapping -or -not $ConfirmTypeNames -or -not $ConfirmParentKeySemantics)) { throw 'Import requires ConfirmImport plus explicit stage, status, type-name, and parent-key semantic confirmations.' }
if ([string]::IsNullOrWhiteSpace($env:EDU_MIGRATION_ACCESS_TOKEN)) { throw 'EDU_MIGRATION_ACCESS_TOKEN is required in the current process.' }

$nodeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $NodeFile).Hash.ToLowerInvariant()
$relationHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $RelationFile).Hash.ToLowerInvariant()
$runFingerprint = Get-Sha256Text ($SourceSystem + "`0" + $Action + "`0" + $nodeHash + "`0" + $relationHash + "`0" + $BatchSize)
$data = Read-SourceData
$summaryParts = Get-GraphSummary $data; $summary = $summaryParts[0]
foreach ($entry in ($summaryParts[1].GetEnumerator() | Sort-Object Name)) { $summary.stageDistribution[$entry.Name] = $entry.Value }
foreach ($entry in ($summaryParts[2].GetEnumerator() | Sort-Object Name)) { $summary.typeDistribution[$entry.Name] = $entry.Value }

$checkpoint = $null
if (Test-Path -LiteralPath $CheckpointPath) {
    $checkpoint = Get-Content -Raw -LiteralPath $CheckpointPath | ConvertFrom-Json
    if ($checkpoint.runFingerprint -ne $runFingerprint -or $checkpoint.sourceSystem -ne $SourceSystem -or $checkpoint.action -ne $Action) { throw 'Checkpoint fingerprint does not match the current immutable inputs.' }
}
if ($null -eq $checkpoint) {
    $requestId = New-DeterministicUuid ($runFingerprint + ':task')
    $created = Invoke-SafeJsonRequest POST ($ApiBaseUrl.TrimEnd('/') + '/api/v1/admin/knowledge-graph-imports') ([ordered]@{
        clientRequestId = $requestId; sourceSystem = $SourceSystem; dryRun = ($Action -eq 'DryRun')
        nodeFileSha256 = $nodeHash; relationFileSha256 = $relationHash
        expectedNodeCount = $data.Nodes.Count; expectedRelationCount = $data.Relations.Count
    })
    $checkpoint = [ordered]@{ version = 1; runFingerprint = $runFingerprint; sourceSystem = $SourceSystem; action = $Action
        taskId = [string]$created.id; nextNodeIndex = 0; nextRelationIndex = 0; completed = $false
        aggregateCounts = [ordered]@{ nodes = 0; relations = 0 } }
    Save-Checkpoint $checkpoint $CheckpointPath
}

while ([int]$checkpoint.nextNodeIndex -lt $data.Nodes.Count) {
    $start = [int]$checkpoint.nextNodeIndex; $count = [Math]::Min($BatchSize, $data.Nodes.Count - $start); $final = ($start + $count -eq $data.Nodes.Count)
    $body = [ordered]@{ batchId = (New-DeterministicUuid ($runFingerprint + ':node:' + $start)); finalBatch = $final; nodes = @($data.Nodes[$start..($start + $count - 1)]) }
    [void](Invoke-SafeJsonRequest POST ($ApiBaseUrl.TrimEnd('/') + '/api/v1/admin/knowledge-graph-imports/' + $checkpoint.taskId + '/node-batches') $body)
    $checkpoint.nextNodeIndex = $start + $count; $checkpoint.aggregateCounts.nodes = [int]$checkpoint.aggregateCounts.nodes + $count; Save-Checkpoint $checkpoint $CheckpointPath
}
while ([int]$checkpoint.nextRelationIndex -lt $data.Relations.Count) {
    $start = [int]$checkpoint.nextRelationIndex; $count = [Math]::Min($BatchSize, $data.Relations.Count - $start); $final = ($start + $count -eq $data.Relations.Count)
    $body = [ordered]@{ batchId = (New-DeterministicUuid ($runFingerprint + ':relation:' + $start)); finalBatch = $final; relations = @($data.Relations[$start..($start + $count - 1)]) }
    [void](Invoke-SafeJsonRequest POST ($ApiBaseUrl.TrimEnd('/') + '/api/v1/admin/knowledge-graph-imports/' + $checkpoint.taskId + '/relation-batches') $body)
    $checkpoint.nextRelationIndex = $start + $count; $checkpoint.aggregateCounts.relations = [int]$checkpoint.aggregateCounts.relations + $count; Save-Checkpoint $checkpoint $CheckpointPath
}
$remote = Invoke-SafeJsonRequest GET ($ApiBaseUrl.TrimEnd('/') + '/api/v1/admin/knowledge-graph-imports/' + $checkpoint.taskId) $null
$checkpoint.completed = $true; Save-Checkpoint $checkpoint $CheckpointPath
[pscustomobject]@{ action = $Action; sourceSystem = $SourceSystem; taskFingerprint = (Get-Sha256Text ([string]$checkpoint.taskId)).Substring(0, 16)
    local = $summary; remote = $remote.summary; status = $remote.status; formalImportExecuted = ($Action -eq 'Import') }
