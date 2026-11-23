<#
.SYNOPSIS
  Shows the anomaly detection job's status and the demo organization's anomalies.
  DEVELOPMENT ONLY — uses the dev-bootstrap.ps1 demo account.

.EXAMPLE
  ./scripts/dev-anomalies.ps1 -BaseUrl http://localhost:18080
  ./scripts/dev-anomalies.ps1 -BaseUrl http://localhost:18080 -Status all -Minutes 120
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Email = "demo@example.com",
    [string]$Password = "pulseops-local-demo-only",
    [string]$OrgName = "PulseOps Demo",
    [int]$Minutes = 60,
    [ValidateSet("active", "open", "linked", "resolved", "dismissed", "all")]
    [string]$Status = "active"
)
$ErrorActionPreference = "Stop"

function Find-ByName($Items, [string]$Name) {
    foreach ($item in $Items) { if ($item.name -eq $Name) { return $item } }
    return $null
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null) {
    $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; ContentType = "application/json"; Headers = @{} }
    if ($Token) { $request.Headers["Authorization"] = "Bearer $Token" }
    if ($null -ne $Body) { $request.Body = ($Body | ConvertTo-Json -Compress) }
    Invoke-RestMethod @request
}

$token = (Invoke-Api POST "/api/v1/auth/login" @{ email = $Email; password = $Password }).accessToken
$org = Find-ByName (Invoke-Api GET "/api/v1/organizations" -Token $token) $OrgName
if (-not $org) { throw "Organization '$OrgName' not found; run dev-bootstrap.ps1 first." }
$api = "/api/v1/organizations/$($org.id)/anomalies"

$st = Invoke-Api GET "$api/detection-status" -Token $token
Write-Host ""
Write-Host "Detection job" -ForegroundColor Cyan
Write-Host ("  enabled {0}   last run {1}   last success {2}" -f $st.enabled, $st.lastRunAt, $st.lastSuccessAt)
Write-Host ("  services {0}   series {1}   findings {2}" -f $st.servicesEvaluated, $st.seriesEvaluated, $st.anomaliesFound)
if ($st.lastError) { Write-Host "  last error: $($st.lastError)" -ForegroundColor Yellow }

$from = [DateTime]::UtcNow.AddMinutes(-$Minutes).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$page = Invoke-Api GET "$($api)?from=$from&status=$Status&limit=30" -Token $token
Write-Host ""
Write-Host "Anomalies ($Status, last $Minutes minutes)" -ForegroundColor Cyan
if ($page.anomalies.Count -eq 0) { Write-Host "  none"; return }
$page.anomalies | Select-Object @{ n = "detected"; e = { ([DateTime]$_.detectedAt).ToLocalTime().ToString("HH:mm") } },
        severity, status, metric, scope, occurrences,
        @{ n = "reason"; e = { if ($_.reason.Length -gt 95) { $_.reason.Substring(0, 92) + "..." } else { $_.reason } } } |
        Format-Table -AutoSize
