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
    [ValidateRange(1, 20)]
    [int]$BatchSize = 10,
    [string]$ImportTaskId
)

$ErrorActionPreference = 'Stop'
$accessToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
if ([string]::IsNullOrWhiteSpace($accessToken)) { throw 'EDU_MIGRATION_ACCESS_TOKEN is required in the current process.' }
$headers = @{ 'Authorization' = "Bearer $accessToken"; 'Content-Type' = 'application/json' }

function Get-Sha256Hex([byte[]]$Bytes) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}

if ($Action -eq 'Cleanup') {
    if ([string]::IsNullOrWhiteSpace($ImportTaskId)) { throw 'Cleanup requires ImportTaskId.' }
    try {
        $result = Invoke-RestMethod -Method Delete -Uri "$ApiBaseUrl/api/v1/teacher/legacy-question-imports/$ImportTaskId/created-resources" -Headers $headers
    } catch { throw 'Legacy cleanup request failed; response content was suppressed.' }
    [pscustomobject]@{
        importTaskId = $result.importTaskId
        questionsDeleted = $result.questionsDeleted
        sourceReferencesDeleted = $result.sourceReferencesDeleted
        mediaReferencesReleased = $result.mediaReferencesReleased
        alreadyCleaned = $result.alreadyCleaned
    } | ConvertTo-Json -Compress
    exit 0
}

if ([string]::IsNullOrWhiteSpace($SourceRoot)) { throw "$Action requires SourceRoot." }
$resolvedRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
$rootLabel = $resolvedRoot.Replace('\', '/').TrimEnd('/').ToLowerInvariant()
$rootFingerprint = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes($rootLabel))

$createBody = [ordered]@{
    clientRequestId = [guid]::NewGuid()
    sourceSystem = $SourceSystem
    sourceRootFingerprint = $rootFingerprint
    dryRun = ($Action -eq 'DryRun')
    maxItems = $MaxItems
} | ConvertTo-Json -Compress
try {
    $task = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/v1/teacher/legacy-question-imports" -Headers $headers -Body $createBody
} catch { throw 'Legacy import task creation failed; response content was suppressed.' }

$batch = [System.Collections.Generic.List[object]]::new()
$processed = 0
$imported = 0
$valid = 0
$review = 0
$skipped = 0
$failed = 0
$enumerator = [System.IO.Directory]::EnumerateFiles($resolvedRoot, '*.json', [System.IO.SearchOption]::AllDirectories).GetEnumerator()

function Submit-Batch([bool]$FinalBatch) {
    if ($batch.Count -eq 0) { return }
    $body = [ordered]@{ batchId = [guid]::NewGuid(); finalBatch = $FinalBatch; items = @($batch) } | ConvertTo-Json -Depth 30 -Compress
    try {
        $response = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/v1/teacher/legacy-question-imports/$($task.id)/batches" -Headers $headers -Body $body
    } catch {
        throw "Legacy batch request failed after $processed confirmed items. Retry with a new controlled task; response content was suppressed."
    }
    $script:processed += [int]$response.processedCount
    $script:imported += [int]$response.importedCount
    $script:valid += [int]$response.validCount
    $script:review += [int]$response.reviewCount
    $script:skipped += [int]$response.skippedCount
    $script:failed += [int]$response.failedCount
    $batch.Clear()
}

# Sequential processing bounds concurrency at one and memory at one batch of no more than 20 items.
while ($processed + $batch.Count -lt $MaxItems -and $enumerator.MoveNext()) {
    if ($batch.Count -ge $BatchSize) { Submit-Batch $false }
    $path = $enumerator.Current
    try {
        $raw = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
        $json = $raw | ConvertFrom-Json
        # Hash original UTF-8 bytes so idempotency does not depend on PowerShell/Jackson JSON re-serialization details.
        $digest = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes($raw))
        $relative = $path.Substring($resolvedRoot.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
        $parts = $relative -split '/'
        $externalId = [string]$json.exerciseID
        if ([string]::IsNullOrWhiteSpace($externalId)) { $externalId = [System.IO.Path]::GetFileNameWithoutExtension($path) }
        $batch.Add([ordered]@{
            externalId = $externalId
            relativePath = $relative
            pathEdition = $parts[0]
            pathGrade = $parts[1]
            contentSha256 = $digest
            json = $json
        })
    } catch {
        throw 'A selected legacy JSON file could not be parsed; source content and path were suppressed.'
    }
}
$enumerator.Dispose()
if ($processed -eq 0 -and $batch.Count -eq 0) { throw 'No legacy JSON files were selected.' }
Submit-Batch $true

[pscustomobject]@{
    importTaskId = $task.id
    action = $Action
    processedCount = $processed
    importedCount = $imported
    validCount = $valid
    reviewCount = $review
    skippedCount = $skipped
    failedCount = $failed
} | ConvertTo-Json -Compress
