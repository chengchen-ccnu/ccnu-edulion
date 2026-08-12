[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,
    [ValidateRange(0, 1000000)]
    [int]$MaxFiles = 0
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
$typeCounts = @{}
$difficultyCounts = @{}
$shapeCounts = @{}
$total = 0
$parseErrors = 0
$withSubQuestions = 0
$withExternalImages = 0
$emptySolutions = 0
$metadataConflicts = 0

function Add-Count([hashtable]$Map, [string]$Key) {
    if ($Map.ContainsKey($Key)) { $Map[$Key]++ } else { $Map[$Key] = 1 }
}

# Stream one file at a time and retain aggregate counters only; never emit source content.
foreach ($path in [System.IO.Directory]::EnumerateFiles($resolvedRoot, '*.json', [System.IO.SearchOption]::AllDirectories)) {
    if ($MaxFiles -gt 0 -and $total -ge $MaxFiles) { break }
    $total++
    try {
        $raw = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
        $json = $raw | ConvertFrom-Json
        Add-Count $typeCounts ([string]$json.exerciseType)
        Add-Count $difficultyCounts ([string]$json.difficulty)
        Add-Count $shapeCounts (($json.PSObject.Properties.Name | Sort-Object) -join ',')
        if (@($json.subQues).Count -gt 0) { $withSubQuestions++ }
        if ($raw -match '(?i)<img\b[^>]*\bsrc\s*=\s*["'']https://') { $withExternalImages++ }
        if ([string]::IsNullOrWhiteSpace([string]$json.solution)) { $emptySolutions++ }
        $relative = $path.Substring($resolvedRoot.TrimEnd('\').Length).TrimStart('\')
        $pathGrade = ($relative -split '[\\/]')[1]
        $gradeMarker = switch ([int]$json.gradeIndex) { 7 { [string][char]0x4E03 } 8 { [string][char]0x516B } 9 { [string][char]0x4E5D } default { '' } }
        if ([string]::IsNullOrEmpty($gradeMarker) -or $pathGrade -notlike "*$gradeMarker*") { $metadataConflicts++ }
    } catch {
        $parseErrors++
    }
}

[pscustomobject]@{
    totalFiles = $total
    parseErrors = $parseErrors
    keyShapeCount = $shapeCounts.Count
    withSubQuestions = $withSubQuestions
    withExternalImages = $withExternalImages
    emptySolutions = $emptySolutions
    metadataConflicts = $metadataConflicts
    typeCounts = [pscustomobject]$typeCounts
    difficultyCounts = [pscustomobject]$difficultyCounts
} | ConvertTo-Json -Depth 4 -Compress
