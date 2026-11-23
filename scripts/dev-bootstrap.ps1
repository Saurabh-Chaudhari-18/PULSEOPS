<#
.SYNOPSIS
  Creates a demo account, organization, project and "order-service" service in a locally
  running PulseOps backend, configures the Java-agent integration and issues a
  service-scoped integration key. Safe to re-run: existing objects are reused and a new
  key is issued each time.

.DESCRIPTION
  DEVELOPMENT ONLY. Start the backend with PULSEOPS_REQUIRE_EMAIL_VERIFICATION=false first
  (see examples/order-service/README.md). The demo password below is for a local
  throwaway database; never use it anywhere else.

.EXAMPLE
  ./scripts/dev-bootstrap.ps1
  $env:PULSEOPS_INGEST_KEY = '<printed key>'
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Email = "demo@example.com",
    [string]$Password = "pulseops-local-demo-only",
    [string]$OrgName = "PulseOps Demo",
    [string]$ProjectName = "Shop",
    [string]$ServiceName = "order-service"
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

function Get-Status($ErrorRecord) {
    try { return [int]$ErrorRecord.Exception.Response.StatusCode } catch { return 0 }
}

try {
    Invoke-Api GET "/actuator/health" | Out-Null
} catch {
    throw "PulseOps backend is not reachable at $BaseUrl. Start it first (examples/order-service/README.md, step 2)."
}

# 1. Account (409 = already registered: fine).
try {
    Invoke-Api POST "/api/v1/auth/register" @{ email = $Email; password = $Password; fullName = "Demo User" } | Out-Null
    Write-Host "Registered $Email"
} catch {
    if ((Get-Status $_) -ne 409) { throw }
    Write-Host "Account $Email already exists"
}
try {
    $token = (Invoke-Api POST "/api/v1/auth/login" @{ email = $Email; password = $Password }).accessToken
} catch {
    if ((Get-Status $_) -eq 403) {
        throw "Login refused (email not verified). Restart the backend with PULSEOPS_REQUIRE_EMAIL_VERIFICATION=false."
    }
    throw
}

# 2. Organization, project, service (reused by name).
$org = Find-ByName (Invoke-Api GET "/api/v1/organizations" -Token $token) $OrgName
if (-not $org) { $org = Invoke-Api POST "/api/v1/organizations" @{ name = $OrgName } $token; Write-Host "Created organization '$OrgName'" }
$orgPath = "/api/v1/organizations/$($org.id)"

$project = Find-ByName (Invoke-Api GET "$orgPath/projects?size=100" -Token $token).content $ProjectName
if (-not $project) {
    $project = Invoke-Api POST "$orgPath/projects" @{ name = $ProjectName; environment = "development"; techStack = "Spring Boot" } $token
    Write-Host "Created project '$ProjectName'"
}

$service = Find-ByName (Invoke-Api GET "$orgPath/projects/$($project.id)/services?size=100" -Token $token).content $ServiceName
if (-not $service) {
    $service = Invoke-Api POST "$orgPath/projects/$($project.id)/services" @{ name = $ServiceName; technology = "java_spring_boot" } $token
    Write-Host "Created service '$ServiceName'"
}

# 3. Integration method + a key scoped to this one service.
Invoke-Api PUT "$orgPath/services/$($service.id)/integration" @{ type = "JAVA_AGENT" } $token | Out-Null
$stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$credential = Invoke-Api POST "$orgPath/projects/$($project.id)/credentials" @{ name = "$ServiceName agent $stamp"; serviceId = $service.id } $token

Write-Host ""
Write-Host "Integration key (shown once - store it securely):" -ForegroundColor Green
Write-Host "  $($credential.key)"
Write-Host ""
$baseArg = if ($BaseUrl -ne "http://localhost:8080") { " -BaseUrl $BaseUrl" } else { "" }
$otlpArg = if ($BaseUrl -ne "http://localhost:8080") { " -OtlpEndpoint $BaseUrl/otlp" } else { "" }
Write-Host "Next, in this PowerShell window:"
Write-Host "  `$env:PULSEOPS_INGEST_KEY = '$($credential.key)'"
Write-Host "  ./examples/order-service/run-with-agent.ps1$otlpArg"
Write-Host ""
Write-Host "Check the connection with:"
Write-Host "  ./scripts/dev-status.ps1$baseArg"
Write-Host "(organization $($org.id), service $($service.id))"
