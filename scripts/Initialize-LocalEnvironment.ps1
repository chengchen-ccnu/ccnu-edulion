[CmdletBinding()]
param(
    [string]$ConfigDirectory = (
        Join-Path ([Environment]::GetFolderPath("UserProfile")) ".ccnu-edulion"
    ),
    [string]$DefaultMySqlUsername = "root",
    [switch]$ConsolePrompt,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "This initializer requires Windows DPAPI."
}

$configPath = Join-Path $ConfigDirectory "local.env"
$credentialPath = Join-Path $ConfigDirectory "mysql.credential.clixml"
$serviceCredentialPath = Join-Path $ConfigDirectory "course-media-auth.credential.clixml"
$iamSecurityCredentialPath = Join-Path $ConfigDirectory "iam-security.credential.clixml"
$learningIamCredentialPath = Join-Path $ConfigDirectory "learning-iam-service.credential.clixml"
$agentIndexCredentialPath = Join-Path $ConfigDirectory "agent-index-service.credential.clixml"
$agentAccessCredentialPath = Join-Path $ConfigDirectory "agent-access-internal.credential.clixml"

function New-ServiceAuthorizationCredential {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
        $secret = [Convert]::ToBase64String($bytes)
        return [PSCredential]::new(
            "course-media-service",
            (ConvertTo-SecureString $secret -AsPlainText -Force)
        )
    }
    finally {
        $generator.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
        $secret = $null
    }
}

function New-IamSecurityCredential {
    $jwtBytes = New-Object byte[] 32
    $verificationBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($jwtBytes)
        $generator.GetBytes($verificationBytes)
        $payload = @{
            jwtSecret = [Convert]::ToBase64String($jwtBytes)
            verificationHmacSecret = [Convert]::ToBase64String($verificationBytes)
            localVerificationCode = "246810"
        } | ConvertTo-Json -Compress
        return [PSCredential]::new(
            "iam-security",
            (ConvertTo-SecureString $payload -AsPlainText -Force)
        )
    }
    finally {
        $generator.Dispose()
        [Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
        [Array]::Clear($verificationBytes, 0, $verificationBytes.Length)
        $payload = $null
    }
}

function New-LearningIamServiceCredential {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
        $secret = [Convert]::ToBase64String($bytes)
        return [PSCredential]::new(
            "learning-iam-service",
            (ConvertTo-SecureString $secret -AsPlainText -Force)
        )
    }
    finally {
        $generator.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
        $secret = $null
    }
}

function New-AgentIndexServiceCredential {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
        $secret = [Convert]::ToBase64String($bytes)
        return [PSCredential]::new(
            "agent-index-worker",
            (ConvertTo-SecureString $secret -AsPlainText -Force)
        )
    }
    finally {
        $generator.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
        $secret = $null
    }
}

function New-AgentAccessInternalCredential {
    $callbackBytes = New-Object byte[] 32
    $domainBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($callbackBytes)
        $generator.GetBytes($domainBytes)
        $payload = @{
            runtimeCallbackToken = [Convert]::ToBase64String($callbackBytes)
            domainToken = [Convert]::ToBase64String($domainBytes)
        } | ConvertTo-Json -Compress
        return [PSCredential]::new("agent-access-internal", (ConvertTo-SecureString $payload -AsPlainText -Force))
    }
    finally {
        $generator.Dispose()
        [Array]::Clear($callbackBytes, 0, $callbackBytes.Length)
        [Array]::Clear($domainBytes, 0, $domainBytes.Length)
        $payload = $null
    }
}

if (-not $Force -and ((Test-Path -LiteralPath $configPath) -or (Test-Path -LiteralPath $credentialPath))) {
    $existingBaseConfiguration = (Test-Path -LiteralPath $configPath -PathType Leaf) `
        -and (Test-Path -LiteralPath $credentialPath -PathType Leaf)
    $serviceCredentialValid = $false
    if (Test-Path -LiteralPath $serviceCredentialPath -PathType Leaf) {
        try {
            $existingServiceCredential = Import-Clixml -LiteralPath $serviceCredentialPath
            if ($existingServiceCredential -is [PSCredential] `
                    -and $existingServiceCredential.UserName -eq "course-media-service") {
                $existingServiceNetworkCredential = $existingServiceCredential.GetNetworkCredential()
                $serviceCredentialValid = $existingServiceNetworkCredential.Password.Length -ge 32
            }
        }
        catch {
            $serviceCredentialValid = $false
        }
        finally {
            $existingServiceNetworkCredential = $null
            $existingServiceCredential = $null
        }
    }
    $iamSecurityCredentialValid = $false
    if (Test-Path -LiteralPath $iamSecurityCredentialPath -PathType Leaf) {
        try {
            $existingIamCredential = Import-Clixml -LiteralPath $iamSecurityCredentialPath
            if ($existingIamCredential -is [PSCredential] -and $existingIamCredential.UserName -eq "iam-security") {
                $existingIamPayload = $existingIamCredential.GetNetworkCredential().Password | ConvertFrom-Json
                $iamSecurityCredentialValid = $existingIamPayload.jwtSecret.Length -ge 32 `
                    -and $existingIamPayload.verificationHmacSecret.Length -ge 32 `
                    -and $existingIamPayload.localVerificationCode -match "^[0-9]{6}$"
            }
        }
        catch {
            $iamSecurityCredentialValid = $false
        }
        finally {
            $existingIamPayload = $null
            $existingIamCredential = $null
        }
    }
    $learningIamCredentialValid = $false
    if (Test-Path -LiteralPath $learningIamCredentialPath -PathType Leaf) {
        try {
            $existingLearningIamCredential = Import-Clixml -LiteralPath $learningIamCredentialPath
            if ($existingLearningIamCredential -is [PSCredential] `
                    -and $existingLearningIamCredential.UserName -eq "learning-iam-service") {
                $existingLearningIamNetworkCredential = $existingLearningIamCredential.GetNetworkCredential()
                $learningIamCredentialValid = $existingLearningIamNetworkCredential.Password.Length -ge 32
            }
        }
        catch {
            $learningIamCredentialValid = $false
        }
        finally {
            $existingLearningIamNetworkCredential = $null
            $existingLearningIamCredential = $null
        }
    }
    $agentIndexCredentialValid = $false
    if (Test-Path -LiteralPath $agentIndexCredentialPath -PathType Leaf) {
        try {
            $existingAgentIndexCredential = Import-Clixml -LiteralPath $agentIndexCredentialPath
            if ($existingAgentIndexCredential -is [PSCredential] `
                    -and $existingAgentIndexCredential.UserName -eq "agent-index-worker") {
                $existingAgentIndexNetworkCredential = $existingAgentIndexCredential.GetNetworkCredential()
                $agentIndexCredentialValid = $existingAgentIndexNetworkCredential.Password.Length -ge 32
            }
        }
        catch {
            $agentIndexCredentialValid = $false
        }
        finally {
            $existingAgentIndexNetworkCredential = $null
            $existingAgentIndexCredential = $null
        }
    }
    $agentAccessCredentialValid = $false
    if (Test-Path -LiteralPath $agentAccessCredentialPath -PathType Leaf) {
        try {
            $existingAgentAccessCredential = Import-Clixml -LiteralPath $agentAccessCredentialPath
            if ($existingAgentAccessCredential -is [PSCredential] `
                    -and $existingAgentAccessCredential.UserName -eq "agent-access-internal") {
                $existingAgentAccessPayload = $existingAgentAccessCredential.GetNetworkCredential().Password | ConvertFrom-Json
                $agentAccessCredentialValid = $existingAgentAccessPayload.runtimeCallbackToken.Length -ge 32 `
                    -and $existingAgentAccessPayload.domainToken.Length -ge 32
            }
        }
        catch { $agentAccessCredentialValid = $false }
        finally {
            $existingAgentAccessPayload = $null
            $existingAgentAccessCredential = $null
        }
    }
    $existingLocalLines = if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        @(Get-Content -LiteralPath $configPath)
    } else { @() }
    $agentConfigurationValid = ($existingLocalLines -match '^AGENT_MYSQL_DATABASE=').Count -gt 0 `
        -and ($existingLocalLines -match '^AGENT_INDEX_API_ENABLED=').Count -gt 0 `
        -and ($existingLocalLines -match '^AGENT_ACCESS_DOMAIN_API_ENABLED=').Count -gt 0
    if ($existingBaseConfiguration -and (-not $serviceCredentialValid -or -not $iamSecurityCredentialValid `
            -or -not $learningIamCredentialValid -or -not $agentIndexCredentialValid `
            -or -not $agentAccessCredentialValid `
            -or -not $agentConfigurationValid)) {
        New-Item -ItemType Directory -Path $ConfigDirectory -Force | Out-Null
        if (-not $serviceCredentialValid) {
            New-ServiceAuthorizationCredential | Export-Clixml -LiteralPath $serviceCredentialPath -Force
            Write-Host "Added the DPAPI-encrypted course/media service authorization secret."
        }
        if (-not $iamSecurityCredentialValid) {
            New-IamSecurityCredential | Export-Clixml -LiteralPath $iamSecurityCredentialPath -Force
            Write-Host "Added the DPAPI-encrypted IAM security secrets."
        }
        if (-not $learningIamCredentialValid) {
            New-LearningIamServiceCredential | Export-Clixml -LiteralPath $learningIamCredentialPath -Force
            Write-Host "Added the DPAPI-encrypted Learning-to-IAM service secret."
        }
        if (-not $agentIndexCredentialValid) {
            New-AgentIndexServiceCredential | Export-Clixml -LiteralPath $agentIndexCredentialPath -Force
            Write-Host "Added the DPAPI-encrypted Agent Index Worker service secret."
        }
        if (-not $agentAccessCredentialValid) {
            New-AgentAccessInternalCredential | Export-Clixml -LiteralPath $agentAccessCredentialPath -Force
            Write-Host "Added the DPAPI-encrypted Agent Access internal service secrets."
        }
        if (($existingLocalLines -match '^AGENT_MYSQL_DATABASE=').Count -eq 0) {
            Add-Content -LiteralPath $configPath -Value "AGENT_MYSQL_DATABASE=edu_platform_agent_dev" -Encoding UTF8
        }
        if (($existingLocalLines -match '^AGENT_INDEX_API_ENABLED=').Count -eq 0) {
            Add-Content -LiteralPath $configPath -Value "AGENT_INDEX_API_ENABLED=true" -Encoding UTF8
        }
        if (($existingLocalLines -match '^AGENT_ACCESS_DOMAIN_API_ENABLED=').Count -eq 0) {
            Add-Content -LiteralPath $configPath -Value "AGENT_ACCESS_DOMAIN_API_ENABLED=true" -Encoding UTF8
        }
        if (-not $agentConfigurationValid) {
            Write-Host "Added the non-secret Agent local configuration."
        }
        return
    }
    throw "Local configuration already exists. Re-run with -Force only when you intend to replace it."
}

function Read-GuiCredential {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DefaultUsername
    )

    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    $form = New-Object System.Windows.Forms.Form
    $form.Text = "ccnu-edulion local MySQL credential"
    $form.StartPosition = "CenterScreen"
    $form.TopMost = $true
    $form.ClientSize = New-Object System.Drawing.Size(430, 185)
    $form.FormBorderStyle = "FixedDialog"
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false

    $messageLabel = New-Object System.Windows.Forms.Label
    $messageLabel.Location = New-Object System.Drawing.Point(20, 15)
    $messageLabel.Size = New-Object System.Drawing.Size(390, 35)
    $messageLabel.Text = "Enter the local MySQL credential. The password will be encrypted with Windows DPAPI."
    $form.Controls.Add($messageLabel)

    $usernameLabel = New-Object System.Windows.Forms.Label
    $usernameLabel.Location = New-Object System.Drawing.Point(20, 58)
    $usernameLabel.AutoSize = $true
    $usernameLabel.Text = "Username"
    $form.Controls.Add($usernameLabel)

    $usernameTextBox = New-Object System.Windows.Forms.TextBox
    $usernameTextBox.Location = New-Object System.Drawing.Point(115, 55)
    $usernameTextBox.Size = New-Object System.Drawing.Size(295, 23)
    $usernameTextBox.Text = $DefaultUsername
    $form.Controls.Add($usernameTextBox)

    $passwordLabel = New-Object System.Windows.Forms.Label
    $passwordLabel.Location = New-Object System.Drawing.Point(20, 93)
    $passwordLabel.AutoSize = $true
    $passwordLabel.Text = "Password"
    $form.Controls.Add($passwordLabel)

    $passwordTextBox = New-Object System.Windows.Forms.TextBox
    $passwordTextBox.Location = New-Object System.Drawing.Point(115, 90)
    $passwordTextBox.Size = New-Object System.Drawing.Size(295, 23)
    $passwordTextBox.UseSystemPasswordChar = $true
    $form.Controls.Add($passwordTextBox)

    $okButton = New-Object System.Windows.Forms.Button
    $okButton.Location = New-Object System.Drawing.Point(254, 135)
    $okButton.Size = New-Object System.Drawing.Size(75, 28)
    $okButton.Text = "OK"
    $okButton.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $form.AcceptButton = $okButton
    $form.Controls.Add($okButton)

    $cancelButton = New-Object System.Windows.Forms.Button
    $cancelButton.Location = New-Object System.Drawing.Point(335, 135)
    $cancelButton.Size = New-Object System.Drawing.Size(75, 28)
    $cancelButton.Text = "Cancel"
    $cancelButton.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
    $form.CancelButton = $cancelButton
    $form.Controls.Add($cancelButton)

    $form.Add_Shown({
        $passwordTextBox.Select()
        $passwordTextBox.Focus()
    })

    try {
        if ($form.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
            return $null
        }

        $username = $usernameTextBox.Text.Trim()
        if (-not $username) {
            throw "MySQL username cannot be empty."
        }

        $securePassword = ConvertTo-SecureString $passwordTextBox.Text -AsPlainText -Force
        return [PSCredential]::new($username, $securePassword)
    }
    finally {
        $passwordTextBox.Clear()
        $form.Dispose()
    }
}

$credential = if ($ConsolePrompt) {
    Get-Credential `
        -UserName $DefaultMySqlUsername `
        -Message "Enter the local MySQL credential for ccnu-edulion."
}
else {
    Read-GuiCredential -DefaultUsername $DefaultMySqlUsername
}

if ($null -eq $credential) {
    throw "Credential initialization was cancelled."
}

New-Item -ItemType Directory -Path $ConfigDirectory -Force | Out-Null

$credential | Export-Clixml -LiteralPath $credentialPath -Force
New-ServiceAuthorizationCredential | Export-Clixml -LiteralPath $serviceCredentialPath -Force
New-IamSecurityCredential | Export-Clixml -LiteralPath $iamSecurityCredentialPath -Force
New-LearningIamServiceCredential | Export-Clixml -LiteralPath $learningIamCredentialPath -Force
New-AgentIndexServiceCredential | Export-Clixml -LiteralPath $agentIndexCredentialPath -Force
New-AgentAccessInternalCredential | Export-Clixml -LiteralPath $agentAccessCredentialPath -Force

@(
    "# Non-secret local configuration for ccnu-edulion."
    "# MySQL and service credentials are stored separately with Windows DPAPI."
    "SPRING_PROFILES_ACTIVE=local"
    "MYSQL_HOST=127.0.0.1"
    "MYSQL_PORT=3306"
    "MYSQL_SERVER_TIMEZONE=Asia/Shanghai"
    "MYSQL_SSL_ENABLED=false"
    "MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL=true"
    "IAM_MYSQL_DATABASE=edu_platform_iam_dev"
    "COURSE_MYSQL_DATABASE=edu_platform_course_dev"
    "MEDIA_MYSQL_DATABASE=edu_platform_media_dev"
    "LEARNING_MYSQL_DATABASE=edu_platform_learning_dev"
    "OPERATION_MYSQL_DATABASE=edu_platform_operation_dev"
    "AGENT_MYSQL_DATABASE=edu_platform_agent_dev"
    "REDIS_HOST=127.0.0.1"
    "REDIS_PORT=6379"
    "REDIS_SSL_ENABLED=false"
    "GATEWAY_REDIS_DATABASE=0"
    "IAM_REDIS_DATABASE=1"
    "COURSE_REDIS_DATABASE=2"
    "MEDIA_REDIS_DATABASE=3"
    "LEARNING_REDIS_DATABASE=4"
    "OPERATION_REDIS_DATABASE=5"
    "IAM_SEED_ENABLED=false"
    "IAM_VERIFICATION_DELIVERY_MODE=fixed-local"
    "COURSE_SEED_ENABLED=false"
    "AGENT_INDEX_API_ENABLED=true"
    "AGENT_ACCESS_DOMAIN_API_ENABLED=true"
) | Set-Content -LiteralPath $configPath -Encoding UTF8

Write-Host "Local environment initialized in $ConfigDirectory."
Write-Host "The MySQL password and service authorization secrets were encrypted for the current Windows user."
