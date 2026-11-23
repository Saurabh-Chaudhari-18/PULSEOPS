<#
.SYNOPSIS
  Logs and traces for the demo organization: recent warnings/errors, the slowest traces,
  and the span tree of the slowest one (or of -TraceId). DEVELOPMENT ONLY.

.EXAMPLE
  ./scripts/dev-explore.ps1 -BaseUrl http://localhost:18080
  ./scripts/dev-explore.ps1 -BaseUrl http://localhost:18080 -Search "declined" -MinLevel INFO
  ./scripts/dev-explore.ps1 -BaseUrl http://localhost:18080 -TraceId 4bf92f3577b34da6a3ce929d0e0e4736
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Email = "demo@example.com",
    [string]$Password = "pulseops-local-demo-only",
    [string]$OrgName = "PulseOps Demo",
    [int]$Minutes = 15,
    [string]$MinLevel = "WARN",
    [string]$Search = "",
    [string]$TraceId = ""
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
$api = "/api/v1/organizations/$($org.id)"
$from = [DateTime]::UtcNow.AddMinutes(-$Minutes).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$q = if ($Search) { "&q=" + [Uri]::EscapeDataString($Search) } else { "" }

Write-Host ""
Write-Host "Logs ($MinLevel and above, last $Minutes minutes$(if ($Search) { ", containing '$Search'" }))" -ForegroundColor Cyan
$logs = Invoke-Api GET "$api/logs?from=$from&minLevel=$MinLevel&limit=15$q" -Token $token
$logs.logs | Select-Object @{ n = "time"; e = { ([DateTime]$_.occurredAt).ToLocalTime().ToString("HH:mm:ss") } },
        level, serviceName,
        @{ n = "message"; e = { if ($_.message.Length -gt 90) { $_.message.Substring(0, 87) + "..." } else { $_.message } } },
        @{ n = "trace"; e = { if ($_.traceId) { $_.traceId.Substring(0, 8) + "..." } else { "" } } } | Format-Table -AutoSize

if (-not $TraceId) {
    Write-Host "Slowest traces (last $Minutes minutes)" -ForegroundColor Cyan
    $traces = Invoke-Api GET "$api/traces?from=$from&sort=slowest&limit=8" -Token $token
    $traces.traces | Select-Object traceId, rootName, rootServiceName,
            @{ n = "duration"; e = { "{0:N0} ms" -f $_.durationMs } }, spans, errorSpans | Format-Table -AutoSize
    if ($traces.traces.Count -eq 0) { return }
    $TraceId = $traces.traces[0].traceId
}

$t = Invoke-Api GET "$api/traces/$TraceId" -Token $token
Write-Host ("Trace {0}  {1:N0} ms, {2} spans, {3} errors, services: {4}" -f `
        $t.traceId, $t.durationMs, $t.spanCount, $t.errorSpans, ($t.services -join ", ")) -ForegroundColor Cyan
foreach ($s in $t.spans) {
    $indent = "  " * $s.depth
    $flag = if ($s.status -eq "ERROR") { "  [ERROR]" } else { "" }
    Write-Host ("{0,8:N1} ms +{1,8:N1}  {2}{3} {4}{5}" -f $s.durationMs, $s.offsetMs, $indent, $s.kind, $s.name, $flag)
}
if ($t.logs.Count -gt 0) {
    Write-Host "Logs in this trace:"
    foreach ($l in $t.logs) { Write-Host ("  {0} {1}" -f $l.level, $l.message) }
}
