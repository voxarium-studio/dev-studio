# §12.1: hold the actual launcher process, wait for clean shutdown, then run NSIS.
param(
    [Parameter(Mandatory = $true)] [int] $StudioProcess,
    [Parameter(Mandatory = $true)] [long] $StartedAt,
    [Parameter(Mandatory = $true)] [string] $Installer,
    [Parameter(Mandatory = $true)] [long] $InstallerBytes,
    [Parameter(Mandatory = $true)] [string] $Sha512,
    [Parameter(Mandatory = $true)] [string] $ReadyFile,
    [Parameter(Mandatory = $true)] [string] $ApprovalFile
)
$ErrorActionPreference = 'Stop'
$owner = $null
try {
    $expected = [Convert]::FromBase64String($Sha512)
    if ($expected.Length -ne 64) { throw 'Invalid update hash' }
    $owner = [Diagnostics.Process]::GetProcessById($StudioProcess)
    $null = $owner.Handle
    if (([DateTimeOffset] $owner.StartTime).ToUnixTimeMilliseconds() -ne $StartedAt) { throw 'Studio process changed' }
    $ready = [IO.File]::Open($ReadyFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    $ready.Dispose()
    if (-not $owner.WaitForExit(120000)) { throw 'Studio did not exit in time' }
    if (-not [IO.File]::Exists($ApprovalFile)) { return }

    $file = Get-Item -LiteralPath $Installer
    if ($file.Length -ne $InstallerBytes) { throw 'Update file size changed' }
    $hash = [BitConverter]::ToString($expected).Replace('-', '')
    if ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA512).Hash -ne $hash) { throw 'Update checksum changed' }
    $setup = Start-Process -FilePath $file.FullName -ArgumentList '--updated', '--force-run' -PassThru
    if ($setup.WaitForExit(3600000) -and $setup.ExitCode -eq 0) {
        Remove-Item -LiteralPath $file.FullName -Force
    }
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
} finally {
    if ($null -ne $owner) { $owner.Dispose() }
    foreach ($marker in @($ReadyFile, $ApprovalFile)) {
        if ([IO.File]::Exists($marker)) { [IO.File]::Delete($marker) }
    }
}
