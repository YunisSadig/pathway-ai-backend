<#
.SYNOPSIS
  Pathway AI: store the Neon database credentials as the three secrets of the GitHub "sync" environment.

.DESCRIPTION
  LOCAL OWNER TOOL. Do not commit credentials, logs or output of this script. (Windows PowerShell 5.1)

  Reads an UNPOOLED Neon connection string from the Windows clipboard, validates it, converts it to a JDBC URL with
  sslmode=verify-full and sets exactly three secrets in the existing environment `sync` of the repository:
      DB_URL        jdbc:postgresql://<host>:<port>/pathway_ai?sslmode=verify-full
      DB_USERNAME   the database role
      DB_PASSWORD   its password

  Secret values travel only through the standard input of the GitHub CLI (never on a command line), are never written to a
  file, a log or the terminal, and the clipboard is cleared in a finally block on success AND on every failure.
  It does not enable SYNC_ENABLED, start a workflow or touch any database.

  Run (after you have reviewed this file):
    powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\Lenova\Desktop\pathway-ai-backend\scripts\ops\set-sync-secrets.ps1"
#>
# No parameters on purpose: the target cannot be redirected from the command line. Any argument is rejected.
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$script:GhPath = $null
$script:SecretNames = @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')
Set-Variable -Name TargetRepo -Value 'YunisSadig/pathway-ai-backend' -Option ReadOnly, Constant -Scope Script
Set-Variable -Name TargetEnvironment -Value 'sync' -Option ReadOnly, Constant -Scope Script
Set-Variable -Name TargetDatabase -Value 'pathway_ai' -Option ReadOnly, Constant -Scope Script
Set-Variable -Name AllowedBranch -Value 'main' -Option ReadOnly, Constant -Scope Script

# A failure message must never contain a credential: every message below is a fixed text.
function Fail([string]$message) { throw [System.InvalidOperationException]::new($message) }

# --------------------------------------------------------------------------------------------------------------------
# Parsing and validation (pure functions: no clipboard, no network)
# --------------------------------------------------------------------------------------------------------------------
function ConvertFrom-NeonConnectionString {
    param([string]$Text, [string]$ExpectedDatabase)

    if ([string]::IsNullOrWhiteSpace($Text)) { Fail 'The clipboard is empty.' }
    $t = $Text.Trim()
    if ($t.Length -gt 2048) { Fail 'The clipboard content is too long to be a connection string.' }
    if ($t -match '[\r\n\t ]') { Fail 'The clipboard does not hold a single-line connection string (it contains whitespace).' }

    $m = [regex]::Match($t, '^postgres(?:ql)?://(?<u>[^:@/?#]+):(?<p>[^@/?#]+)@(?<h>[^/:?#]+)(?::(?<port>\d{1,5}))?/(?<d>[^?#]+)(?:\?(?<q>[^#]*))?$')
    if (-not $m.Success) { Fail 'The clipboard is not a PostgreSQL connection string of the form postgresql://user:password@host/database.' }

    $user = [System.Uri]::UnescapeDataString($m.Groups['u'].Value)
    $password = [System.Uri]::UnescapeDataString($m.Groups['p'].Value)
    $dbhost = $m.Groups['h'].Value.ToLowerInvariant()
    $database = [System.Uri]::UnescapeDataString($m.Groups['d'].Value)
    $port = 5432
    if ($m.Groups['port'].Success) { $port = [int]$m.Groups['port'].Value }

    if ($database -cne $ExpectedDatabase) { Fail "The connection string is not for the database '$ExpectedDatabase'." }
    if ($user -notmatch '^[A-Za-z0-9_.-]{1,63}$') { Fail 'The database user name has an unexpected shape.' }
    if ($password.Length -lt 8 -or $password.Length -gt 256 -or $password -match '[\x00-\x20\x7f]') { Fail 'The database password has an unexpected shape.' }
    if ($port -lt 1 -or $port -gt 65535) { Fail 'The port is out of range.' }
    if ($dbhost -notmatch '^[a-z0-9-]+(\.[a-z0-9-]+)*\.neon\.tech$') { Fail 'The host is not a Neon endpoint (*.neon.tech).' }
    if ($dbhost.Split('.')[0] -match 'pooler') { Fail 'This is a POOLED Neon endpoint. Copy the UNPOOLED connection string (turn "Connection pooling" off).' }

    # query parameters: only a short allow-list is understood; anything else is refused rather than guessed
    if ($m.Groups['q'].Success -and $m.Groups['q'].Value.Length -gt 0) {
        foreach ($pair in $m.Groups['q'].Value.Split('&')) {
            $name = $pair.Split('=')[0]
            if ($name -notin @('sslmode', 'channel_binding')) { Fail 'The connection string has a query parameter this script does not accept.' }
        }
    }

    [pscustomobject]@{
        Host     = $dbhost
        Port     = $port
        Database = $database
        User     = $user
        Password = $password
        JdbcUrl  = "jdbc:postgresql://${dbhost}:${port}/${database}?sslmode=verify-full"
    }
}

# Safe to show: the endpoint with its identifying part shortened, never user or password.
function Format-EndpointHint([string]$HostName) {
    $labels = $HostName.Split('.')
    $first = $labels[0]
    $short = if ($first.Length -gt 6) { $first.Substring(0, 6) + '...' } else { $first }
    return $short + '.' + (($labels | Select-Object -Skip 1) -join '.')
}

function Protect-Text([string]$Text, [string[]]$Secrets) {
    if ($null -eq $Text) { return '' }
    foreach ($s in $Secrets) { if ($s -and $s.Length -ge 3) { $Text = $Text.Replace($s, '***') } }
    return $Text
}

# --------------------------------------------------------------------------------------------------------------------
# GitHub CLI: arguments are fixed, validated tokens; secret values go only through standard input
# --------------------------------------------------------------------------------------------------------------------
# Every function that talks to GitHub about secrets refuses any repository or environment other than the pinned pair.
function Assert-Target([string]$Repo, [string]$Environment) {
    if ($Repo -cne $script:TargetRepo -or $Environment -cne $script:TargetEnvironment) {
        Fail 'Refused: this tool only works on the pinned repository and environment.'
    }
}

function Resolve-Gh {
    $cmd = Get-Command gh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $cmd) { Fail 'The GitHub CLI (gh) was not found. Install it and run: gh auth login' }
    return $cmd.Source
}

function Invoke-Gh {
    param([string[]]$GhArgs, [byte[]]$StdinBytes = $null)

    foreach ($a in $GhArgs) {
        if ($a -notmatch '^[A-Za-z0-9_./:=@\[\]-]+$') { Fail 'Internal error: an unsafe gh argument was refused.' }
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:GhPath
    $psi.Arguments = ($GhArgs -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.EnvironmentVariables['GH_PROMPT_DISABLED'] = '1'
    $psi.EnvironmentVariables['NO_COLOR'] = '1'
    $psi.EnvironmentVariables['GH_DEBUG'] = ''   # debug output could echo request details

    # .NET Framework wraps the child's stdin in a StreamWriter using the console code page; under UTF-8 (65001) that writer emits a
    # BOM (EF BB BF) in front of our bytes and would corrupt every secret. The secret is written as raw bytes below, so the code
    # page only needs to be one WITHOUT a preamble while the process starts; the previous one is restored immediately.
    $prevEncoding = $null
    try { $prevEncoding = [Console]::InputEncoding; [Console]::InputEncoding = [System.Text.Encoding]::GetEncoding(437) } catch { $prevEncoding = $null }
    try { $p = [System.Diagnostics.Process]::Start($psi) }
    finally { if ($null -ne $prevEncoding) { try { [Console]::InputEncoding = $prevEncoding } catch { } } }
    try {
        $outTask = $p.StandardOutput.ReadToEndAsync()
        $errTask = $p.StandardError.ReadToEndAsync()
        if ($null -ne $StdinBytes -and $StdinBytes.Length -gt 0) {
            $p.StandardInput.BaseStream.Write($StdinBytes, 0, $StdinBytes.Length)
            $p.StandardInput.BaseStream.Flush()
        }
        $p.StandardInput.Close()
        if (-not $p.WaitForExit(60000)) { try { $p.Kill() } catch { }; Fail 'The GitHub CLI did not answer within 60 seconds.' }
        [pscustomobject]@{ ExitCode = $p.ExitCode; Out = $outTask.Result; Err = $errTask.Result }
    }
    finally { $p.Dispose() }
}

function Set-GhSecret {
    param([string]$Name, [string]$Value, [string]$Repo, [string]$Environment)
    Assert-Target $Repo $Environment
    if ($script:SecretNames -cnotcontains $Name) { Fail 'Refused: not one of the three allowed secret names.' }
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($Value)   # raw UTF-8, no BOM, no trailing newline
    try {
        $r = Invoke-Gh -GhArgs @('secret', 'set', $Name, '--env', $Environment, '--repo', $Repo) -StdinBytes $bytes
    }
    finally { [System.Array]::Clear($bytes, 0, $bytes.Length) }
    if ($r.ExitCode -ne 0) {
        $detail = (Protect-Text $r.Err @($Value)).Trim()
        if ($detail.Length -gt 300) { $detail = $detail.Substring(0, 300) }
        Fail "Setting $Name failed (gh exit code $($r.ExitCode)). $detail"
    }
}

function Remove-GhSecret {
    param([string]$Name, [string]$Repo, [string]$Environment)
    Assert-Target $Repo $Environment
    if ($script:SecretNames -cnotcontains $Name) { Fail 'Refused: not one of the three allowed secret names.' }
    $r = Invoke-Gh -GhArgs @('secret', 'delete', $Name, '--env', $Environment, '--repo', $Repo)
    return ($r.ExitCode -eq 0)
}

# Read-only checks: authenticated, repository reachable, the environment exists. Returns the secret names that already exist.
function Test-GitHubTarget {
    param([string]$Repo, [string]$Environment)
    Assert-Target $Repo $Environment

    $auth = Invoke-Gh -GhArgs @('auth', 'status')
    if ($auth.ExitCode -ne 0) { Fail 'The GitHub CLI is not authenticated. Run: gh auth login' }

    $envCheck = Invoke-Gh -GhArgs @('api', "repos/$Repo/environments/$Environment", '--jq', '.name')
    if ($envCheck.ExitCode -ne 0 -or $envCheck.Out.Trim() -cne $Environment) {
        Fail "The environment '$Environment' of $Repo was not found or you lack admin rights on it. Create it first (docs/deployment-sync.md)."
    }
    # the environment must still restrict deployments to the main branch, and to nothing else
    $pol = Invoke-Gh -GhArgs @('api', "repos/$Repo/environments/$Environment", '--jq', '.deployment_branch_policy.custom_branch_policies')
    $names = Invoke-Gh -GhArgs @('api', "repos/$Repo/environments/$Environment/deployment-branch-policies", '--jq', '.branch_policies[].name')
    $allowed = @($names.Out -split "?
" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($pol.ExitCode -ne 0 -or $names.ExitCode -ne 0 -or $pol.Out.Trim() -cne 'true' -or $allowed.Count -ne 1 -or $allowed[0] -cne $script:AllowedBranch) {
        Fail "The environment '$Environment' does not restrict deployments to the '$($script:AllowedBranch)' branch only. Fix that first."
    }
    $list = Invoke-Gh -GhArgs @('secret', 'list', '--env', $Environment, '--repo', $Repo, '--json', 'name', '--jq', '.[].name')
    if ($list.ExitCode -ne 0) { Fail 'The existing secrets of the environment could not be listed.' }
    return @($list.Out -split "\r?\n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Clear-ClipboardSafely {
    try {
        Set-Clipboard -Value $null
        $left = Get-Clipboard -Raw -ErrorAction SilentlyContinue
        if ($left) { Write-Warning 'The clipboard could not be verified as empty. Clear it manually.' }
        else { Write-Host 'Clipboard cleared.' }
    }
    catch { Write-Warning 'The clipboard could not be cleared. Clear it manually.' }
}

# --------------------------------------------------------------------------------------------------------------------
function Invoke-Main {
    $exit = 1
    $conn = $null
    $created = New-Object System.Collections.Generic.List[string]
    try {
        $script:GhPath = Resolve-Gh
        $existing = @(Test-GitHubTarget -Repo $script:TargetRepo -Environment $script:TargetEnvironment)

        $raw = Get-Clipboard -Raw
        $conn = ConvertFrom-NeonConnectionString -Text $raw -ExpectedDatabase $script:TargetDatabase
        $raw = $null

        Write-Host ''
        Write-Host "Repository   : $($script:TargetRepo)"
        Write-Host "Environment  : $($script:TargetEnvironment) (deployments restricted to $($script:AllowedBranch))"
        Write-Host "Database     : $($conn.Database)  (unpooled endpoint $(Format-EndpointHint $conn.Host), port $($conn.Port))"
        Write-Host 'TLS          : sslmode=verify-full (certificate chain and host name verified)'
        Write-Host "Secrets      : $($script:SecretNames -join ', ')"
        $overwrite = @($script:SecretNames | Where-Object { $existing -contains $_ })
        if ($overwrite.Count -gt 0) { Write-Warning "These already exist and would be REPLACED (not restorable): $($overwrite -join ', ')" }
        Write-Host ''
        $answer = Read-Host 'Type YES to store these three secrets (anything else cancels)'
        if ($answer -cne 'YES') { Fail 'Cancelled. Nothing was changed.' }

        $values = [ordered]@{ DB_URL = $conn.JdbcUrl; DB_USERNAME = $conn.User; DB_PASSWORD = $conn.Password }
        foreach ($name in $values.Keys) {
            Set-GhSecret -Name $name -Value $values[$name] -Repo $script:TargetRepo -Environment $script:TargetEnvironment
            if ($existing -notcontains $name) { $created.Add($name) }
            Write-Host "  set $name"
        }
        Write-Host ''
        Write-Host "Done: 3 secrets stored in environment '$($script:TargetEnvironment)'. SYNC_ENABLED was not changed; nothing was triggered."
        $exit = 0
    }
    catch {
        Write-Host ''
        Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
        foreach ($name in $created) {
            $ok = $false
            try { $ok = Remove-GhSecret -Name $name -Repo $script:TargetRepo -Environment $script:TargetEnvironment } catch { }
            if ($ok) { Write-Host "  rolled back $name" } else { Write-Warning "could not roll back $name; delete it in the GitHub UI" }
        }
    }
    finally {
        $conn = $null
        $values = $null
        Clear-ClipboardSafely
        [System.GC]::Collect()
        Write-Host 'Also clear Windows clipboard history (Win+V) if it is enabled, so no copy of the connection string remains.'
    }
    return $exit
}

# Dot-sourcing (the non-secret tests) loads the functions without running anything.
if ($MyInvocation.InvocationName -eq '.') { return }
exit (Invoke-Main)
