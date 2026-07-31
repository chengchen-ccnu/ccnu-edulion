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

if (-not $Force -and ((Test-Path -LiteralPath $configPath) -or (Test-Path -LiteralPath $credentialPath))) {
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

@(
    "# Non-secret local configuration for ccnu-edulion."
    "# The MySQL credential is stored separately with Windows DPAPI."
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
    "COURSE_SEED_ENABLED=false"
) | Set-Content -LiteralPath $configPath -Encoding UTF8

Write-Host "Local environment initialized in $ConfigDirectory."
Write-Host "The MySQL password was encrypted for the current Windows user and was not written to the repository."
