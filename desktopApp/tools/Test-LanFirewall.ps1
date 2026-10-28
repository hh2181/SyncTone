#Requires -RunAsAdministrator
<#
Creates or updates one temporary, narrowly scoped rule for a LAN connectivity test.
Run manually in an elevated PowerShell. Use -WhatIf to preview, -Remove to undo.
The app's listening port changes when its sync service restarts.
#>
[CmdletBinding(SupportsShouldProcess, DefaultParameterSetName = 'Create')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Create')][string]$ProgramPath,
    [Parameter(Mandatory, ParameterSetName = 'Create')][string]$LocalAddress,
    [Parameter(Mandatory, ParameterSetName = 'Create')][string]$RemoteAddress,
    [Parameter(Mandatory, ParameterSetName = 'Create')][ValidateRange(1, 65535)][int]$LocalPort,
    [Parameter(Mandatory, ParameterSetName = 'Create')][string]$InterfaceAlias,
    [Parameter(ParameterSetName = 'Create')][ValidateSet('Public', 'Private')][string]$Profile = 'Public',
    [Parameter(Mandatory, ParameterSetName = 'Remove')][switch]$Remove
)
$ErrorActionPreference = 'Stop'
$ruleName = 'SyncTone-LAN-Diagnostic'
$ruleDisplayName = 'SyncTone LAN diagnostic (temporary)'
$existingRule = Get-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -ErrorAction SilentlyContinue
if ($existingRule -and $existingRule.DisplayName -ne $ruleDisplayName) {
    throw 'The rule name is used by another rule. No changes were made.'
}
if ($Remove) {
    if ($existingRule -and $PSCmdlet.ShouldProcess($ruleName, 'Remove the diagnostic firewall rule')) {
        Remove-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -ErrorAction Stop
    }
    return
}
$resolvedProgram = (Resolve-Path -LiteralPath $ProgramPath).ProviderPath
if ([IO.Path]::GetFileName($resolvedProgram) -ine 'SyncTone.exe' -or
    -not (Test-Path -LiteralPath $resolvedProgram -PathType Leaf)) {
    throw 'ProgramPath must point to the existing SyncTone.exe being tested.'
}
foreach ($candidate in @($LocalAddress, $RemoteAddress)) {
    $parsedAddress = $null
    if (-not [Net.IPAddress]::TryParse($candidate, [ref]$parsedAddress) -or
        $parsedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
        throw 'LocalAddress and RemoteAddress must be individual private IPv4 addresses.'
    }
    $octets = $parsedAddress.GetAddressBytes()
    if (-not ($octets[0] -eq 10 -or ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31) -or
        ($octets[0] -eq 192 -and $octets[1] -eq 168))) {
        throw 'Only private IPv4 addresses are accepted.'
    }
}
if (-not (Get-NetIPAddress -InterfaceAlias $InterfaceAlias -AddressFamily IPv4 |
    Where-Object IPAddress -EQ $LocalAddress)) {
    throw 'LocalAddress is not assigned to the specified network interface.'
}
$scopeDescription = "$resolvedProgram; $RemoteAddress -> ${LocalAddress}:$LocalPort; $InterfaceAlias; $Profile"
if ($PSCmdlet.ShouldProcess($scopeDescription, 'Create or update the scoped diagnostic TCP rule')) {
    $ruleOptions = @{
        Name = $ruleName; Description = $scopeDescription; Direction = 'Inbound'
        Action = 'Allow'; Enabled = 'True'; Protocol = 'TCP'; Program = $resolvedProgram
        LocalAddress = $LocalAddress; LocalPort = $LocalPort; RemoteAddress = $RemoteAddress
        InterfaceAlias = $InterfaceAlias; Profile = $Profile; EdgeTraversalPolicy = 'Block'
        PolicyStore = 'PersistentStore'
    }
    if ($existingRule) {
        Set-NetFirewallRule @ruleOptions -PassThru
    } else {
        New-NetFirewallRule @ruleOptions -DisplayName $ruleDisplayName
    }
}
