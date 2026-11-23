<#
.SYNOPSIS
  Prints what the PulseOps monitoring APIs report for the demo service: health, summary
  and the endpoint table. DEVELOPMENT ONLY — uses the dev-bootstrap.ps1 demo account.

.EXAMPLE
  ./scripts/dev-monitoring.ps1 -BaseUrl http://localhost:18080
  ./scripts/dev-monitoring.ps1 -BaseUrl http://localhost:18080 -Minutes 15 -Sort p95
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Email = "demo@example.com",
    [string]$Password = "pulseops-local-demo-only",
    [string]$OrgName = "PulseOps Demo",
    [string]$ServiceName = "order-service",
    [int]$Minutes = 60,
    [ValidateSet("requests", "errors", "error_rate", "p95", "p99", "avg", "route")]
    [string]$Sort = "requests"
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

function Format-Pct($fraction) { if ($null -eq $fraction) { "-" } else { "{0:N1}%" -f ($fraction * 100) } }
function Format-Ms($ms) { if ($null -eq $ms) { "-" } else { "{0:N0} ms" -f $ms } }

$token = (Invoke-Api POST "/api/v1/auth/login" @{ email = $Email; password = $Password }).accessToken
$org = Find-ByName (Invoke-Api GET "/api/v1/organizations" -Token $token) $OrgName
if (-not $org) { throw "Organization '$OrgName' not found; run dev-bootstrap.ps1 first." }
$service = Find-ByName (Invoke-Api GET "/api/v1/organizations/$($org.id)/services?size=100" -Token $token).content $ServiceName
if (-not $service) { throw "Service '$ServiceName' not found; run dev-bootstrap.ps1 first." }

$from = [DateTime]::UtcNow.AddMinutes(-$Minutes).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$base = "/api/v1/organizations/$($org.id)/services/$($service.id)/monitoring"
$s = Invoke-Api GET "$base/summary?from=$from" -Token $token

Write-Host ""
Write-Host "$ServiceName - last $Minutes minutes  [$($s.health.status)]  connection: $($s.connectionStatus)" -ForegroundColor Cyan
foreach ($reason in $s.health.reasons) { Write-Host "  - $reason" }
Write-Host ("Requests {0}  ({1:N2}/s)   server errors {2} ({3})   client errors {4}" -f `
        $s.requests, $s.requestsPerSecond, $s.serverErrors, (Format-Pct $s.errorRate), $s.clientErrors)
Write-Host ("Latency  avg {0}   p50 {1}   p95 {2}   p99 {3}   max {4}" -f `
        (Format-Ms $s.avgMs), (Format-Ms $s.p50Ms), (Format-Ms $s.p95Ms), (Format-Ms $s.p99Ms), (Format-Ms $s.maxMs))

$e = Invoke-Api GET "$base/endpoints?from=$from&sort=$Sort" -Token $token
$e.endpoints | Select-Object method, route, requests,
        @{ n = "errors"; e = { $_.serverErrors } },
        @{ n = "error rate"; e = { Format-Pct $_.errorRate } },
        @{ n = "avg"; e = { Format-Ms $_.avgMs } },
        @{ n = "p50"; e = { Format-Ms $_.p50Ms } },
        @{ n = "p95"; e = { Format-Ms $_.p95Ms } },
        @{ n = "p99"; e = { Format-Ms $_.p99Ms } } | Format-Table -AutoSize

$r = Invoke-Api GET "$base/runtime?from=$from" -Token $token
if ($r.available) {
    $last = $r.points | Where-Object { $null -ne $_.heapUsedBytes -or $null -ne $_.cpuUtilization } | Select-Object -Last 1
    Write-Host ("JVM (latest)  CPU {0}   heap {1:N0} MB used   threads {2}" -f `
            (Format-Pct $last.cpuUtilization), ($last.heapUsedBytes / 1MB), $last.threads)
} else {
    Write-Host "No JVM runtime metrics received in this window."
}
