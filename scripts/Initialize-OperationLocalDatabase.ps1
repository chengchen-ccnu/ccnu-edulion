#requires -Version 7.0
[CmdletBinding()]
param([ValidateSet('local','test')][string]$Profile='local')
# 显式初始化仅创建明确Operation开发/测试Schema；业务结构由Operation首个Flyway迁移创建，无生产自动建库。
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    $schema = if ($Profile -eq 'local') { 'edu_platform_operation_dev' } else { 'edu_platform_operation_test' }
    if ($env:OPERATION_MYSQL_DATABASE -ne $schema) { throw 'Operation Schema does not match the explicitly selected local/test profile.' }
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD','Process')
    try {
        $env:MYSQL_PWD=$env:MYSQL_PASSWORD
        & mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME --execute="CREATE DATABASE IF NOT EXISTS ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
        if ($LASTEXITCODE -ne 0) { throw 'Operation local/test initialization failed.' }
        Write-Output "Explicit $Profile Operation Schema initialized; Flyway remains responsible for business tables."
    } finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD',$previousPassword,'Process') }
}
