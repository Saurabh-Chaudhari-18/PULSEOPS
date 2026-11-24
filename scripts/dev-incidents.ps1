<#
.SYNOPSIS
  Lists the demo organization's incidents and shows one in detail (anomalies, timeline,
  evidence). Can also acknowledge or resolve it. DEVELOPMENT ONLY — uses the
  dev-bootstrap.ps1 demo account.

.EXAMPLE
  ./scripts/dev-incidents.ps1 -BaseUrl http://localhost:18080
  ./scripts/dev-incidents.ps1 -BaseUrl http://localhost:18080 -Status all -Minutes 180
  ./scripts/dev-incidents.ps1 -BaseUrl http://localhost:18080 -Action acknowledge
  ./scripts/dev-incidents.ps1 -BaseUrl http://localhost:18080 -Action resolve -Notes "Slow query fixed"
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Email = "demo@example.com",
    [string]$Password = "pulseops-local-demo-only",
    [string]$OrgName = "PulseOps Demo",
    [int]$Minutes = 120,
    [ValidateSet("active", "open", "investigating", "acknowledged", "resolved", "closed", "all")]
    [string]$Status = "active",
    [string]$Id = "",
    [ValidateSet("none", "investigate", "acknowledge", "resolve", "close", "reopen")]
    [string]$Action = "none",
    [string]$Notes = ""
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

function Local-Time($Value) {
    if (-not $Value) { return "" }
    return ([DateTime]$Value).ToLocalTime().ToString("HH:mm:ss")
}

function Short([string]$Text, [int]$Max = 90) {
    if (-not $Text) { return "" }
    $one = $Text -replace "\s+", " "
    if ($one.Length -gt $Max) { return $one.Substring(0, $Max - 3) + "..." }
    return $one
}

$token = (Invoke-Api POST "/api/v1/auth/login" @{ email = $Email; password = $Password }).accessToken
$org = Find-ByName (Invoke-Api GET "/api/v1/organizations" -Token $token) $OrgName
if (-not $org) { throw "Organization '$OrgName' not found; run dev-bootstrap.ps1 first." }
$api = "/api/v1/organizations/$($org.id)/incidents"

$from = [DateTime]::UtcNow.AddMinutes(-$Minutes).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$page = Invoke-Api GET "$($api)?from=$from&status=$Status&limit=20" -Token $token
Write-Host ""
Write-Host "Incidents ($Status, last $Minutes minutes)" -ForegroundColor Cyan
if ($page.incidents.Count -eq 0 -and -not $Id) {
    Write-Host "  none (anomalies open an incident when two arrive together or one is high/critical)"
    return
}
$page.incidents | Select-Object @{ n = "detected"; e = { Local-Time $_.detectedAt } }, severity, status, source,
        @{ n = "anomalies"; e = { $_.anomalyCount } }, @{ n = "title"; e = { Short $_.title 70 } } |
        Format-Table -AutoSize

if (-not $Id) { $Id = $page.incidents[0].id }

$targets = @{ investigate = "investigating"; acknowledge = "acknowledged"; resolve = "resolved"; close = "closed"; reopen = "open" }
if ($Action -ne "none") {
    $body = @{ status = $targets[$Action] }
    if ($Notes) { $body.resolutionNotes = $Notes }
    $null = Invoke-Api PATCH "$api/$Id" $body -Token $token
    Write-Host "Incident set to $($targets[$Action])." -ForegroundColor Green
}

$d = Invoke-Api GET "$api/$Id" -Token $token
$i = $d.incident
Write-Host ""
Write-Host ("Incident: {0}" -f $i.title) -ForegroundColor Cyan
Write-Host ("  {0} / {1} / {2}   detected {3}   services: {4}" -f $i.severity, $i.status, $i.source,
        (Local-Time $i.detectedAt), ($d.affectedServices -join ", "))
if ($i.assigneeName) { Write-Host "  assigned to $($i.assigneeName)" }
if ($i.resolutionNotes) { Write-Host "  resolution: $($i.resolutionNotes)" }
if ($i.summary) {
    Write-Host "  summary:"
    foreach ($line in ($i.summary -split "`n")) { Write-Host "    $line" }
}

Write-Host ""
Write-Host "Linked anomalies" -ForegroundColor Cyan
if ($d.anomalies.Count -eq 0) { Write-Host "  none" } else {
    $d.anomalies | Select-Object severity, status, metric, scope, occurrences,
            @{ n = "reason"; e = { Short $_.reason 80 } } | Format-Table -AutoSize
}

Write-Host "Timeline" -ForegroundColor Cyan
foreach ($e in $d.timeline) {
    $who = if ($e.actorName) { $e.actorName } else { "PulseOps" }
    $change = if ($e.oldValue -or $e.newValue) { " $($e.oldValue) -> $($e.newValue)" } else { "" }
    Write-Host ("  {0}  {1,-20} {2}{3}" -f (Local-Time $e.at), $e.type, $who, $change)
}

$ev = $d.evidence
Write-Host ""
Write-Host ("Evidence {0} - {1}" -f (Local-Time $ev.from), (Local-Time $ev.to)) -ForegroundColor Cyan
Write-Host ("  {0} warning/error logs, {1} slow traces, {2} failing traces, {3} slow statements" -f `
        $ev.logs.Count, $ev.slowestTraces.Count, $ev.errorTraces.Count, $ev.slowestQueries.Count)
foreach ($q in @($ev.slowestQueries | Select-Object -First 3)) {
    Write-Host ("  query p95 {0} ms  calls {1}  {2}" -f [math]::Round($q.p95Ms, 1), $q.calls, (Short $q.operation 70))
}
foreach ($t in @($ev.slowestTraces | Select-Object -First 3)) {
    $name = if ($t.entryName) { $t.entryName } else { $t.rootName }
    Write-Host ("  trace {0} ms  {1}  {2}" -f [math]::Round($t.durationMs, 0), $t.traceId, $name)
}
foreach ($l in @($ev.logs | Select-Object -First 3)) {
    Write-Host ("  log {0} {1} {2}" -f (Local-Time $l.occurredAt), $l.level, (Short $l.message 80))
}
