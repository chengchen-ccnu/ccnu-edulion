# 输入 Invoke-RestMethod 的失败记录，输出只含 HTTP 状态、统一错误码、Trace 和字段错误的诊断对象。
# 响应 message/path/原始正文和异常消息均不进入输出；无法识别的响应只保留状态码。
function ConvertTo-SafeMigrationHttpDiagnostic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [System.Management.Automation.ErrorRecord]$ErrorRecord,
        [string[]]$SensitiveValues = @()
    )

    $status = $null
    try {
        $statusValue = $ErrorRecord.Exception.Response.StatusCode
        if ($null -ne $statusValue) { $status = [int]$statusValue }
    } catch { $status = $null }

    $responseText = $null
    if ($null -ne $ErrorRecord.ErrorDetails -and
            -not [string]::IsNullOrWhiteSpace([string]$ErrorRecord.ErrorDetails.Message)) {
        $responseText = [string]$ErrorRecord.ErrorDetails.Message
    } else {
        try {
            $response = $ErrorRecord.Exception.Response
            if ($null -ne $response -and $null -ne $response.Content -and
                    $null -ne $response.Content.PSObject.Methods['ReadAsStringAsync']) {
                $responseText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            } elseif ($null -ne $response -and $null -ne $response.PSObject.Methods['GetResponseStream']) {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $true, 1024, $false)
                    try { $responseText = $reader.ReadToEnd() } finally { $reader.Dispose() }
                }
            }
        } catch { $responseText = $null }
    }

    $payload = $null
    if (-not [string]::IsNullOrWhiteSpace($responseText) -and $responseText.Length -le 1024 * 1024) {
        try {
            $candidate = $responseText | ConvertFrom-Json
            if ($null -ne $candidate -and $candidate -isnot [Array] -and
                    $candidate -is [System.Management.Automation.PSCustomObject]) {
                $payload = $candidate
            }
        } catch { $payload = $null }
    }

    $code = $null
    $traceId = $null
    $fieldErrors = New-Object System.Collections.Generic.List[object]
    if ($null -ne $payload) {
        $candidateCode = [string]$payload.code
        if ($candidateCode -match '^[A-Z][A-Z0-9_]*\.[A-Z][A-Z0-9_]*$' -and $candidateCode.Length -le 160) {
            $code = $candidateCode
        }
        $candidateTraceId = [string]$payload.traceId
        if ($candidateTraceId -match '^[A-Za-z0-9_-]{1,128}$') { $traceId = $candidateTraceId }

        foreach ($item in @($payload.fieldErrors)) {
            if ($fieldErrors.Count -ge 100) { break }
            if ($null -eq $item -or $item -isnot [System.Management.Automation.PSCustomObject]) { continue }
            $field = [string]$item.field
            if ($field -notmatch '^[A-Za-z0-9_.\[\]-]{1,256}$') { $field = 'request' }
            $reason = if ($null -eq $item.reason -or $item.reason -is [Array] -or
                    $item.reason -is [System.Management.Automation.PSCustomObject]) {
                'No safe field reason was provided.'
            } else { [string]$item.reason }
            $reason = $reason -replace '[\r\n\t]+', ' '
            if ($reason.Length -gt 4000) { $reason = $reason.Substring(0, 4000) }
            if ([Regex]::IsMatch($reason, '(?s)[\{\[].*"[A-Za-z0-9_]+"\s*:')) {
                $reason = 'Structured source content was suppressed.'
            } else {
                foreach ($sensitive in @($SensitiveValues)) {
                    if (-not [string]::IsNullOrWhiteSpace($sensitive)) {
                        $reason = $reason.Replace([string]$sensitive, '[REDACTED]')
                    }
                }
                # 即使后端错误地回显了本机路径或 Bearer/JWT，也不将其交给终端。
                $reason = [Regex]::Replace($reason, '(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*', 'Bearer [REDACTED]')
                $reason = [Regex]::Replace($reason, '(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])', '[REDACTED]')
                $reason = [Regex]::Replace($reason, '(?i)(?<![A-Za-z0-9])(?:[A-Z]:[\\/]|\\\\)[^\s"''<>|]+', '[REDACTED_PATH]')
            }
            if ($reason.Length -gt 500) { $reason = $reason.Substring(0, 500) + '...' }
            $fieldErrors.Add([pscustomobject][ordered]@{ field = $field; reason = $reason })
        }
    }

    return [pscustomobject][ordered]@{
        httpStatus = $status
        code = $code
        traceId = $traceId
        fieldErrors = $fieldErrors.ToArray()
    }
}
