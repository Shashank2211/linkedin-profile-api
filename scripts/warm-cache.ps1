<#
.SYNOPSIS
    Primes the deployed cache so a reviewer's first request is served from memory.

.DESCRIPTION
    The service is deliberately slow at talking to LinkedIn — PaceGate enforces a gap
    between outbound calls and a throttled session goes into cooldown for minutes. That is
    correct behaviour, and it is also exactly what you do not want happening while someone
    is watching.

    This walks a short list of profiles once, with a pause between each, so each one lands
    in the cache. With CACHE_STALE_TTL at its default of 24 hours they stay servable for a
    day even if the session dies afterwards — meta.stale will report that honestly.

.EXAMPLE
    .\scripts\warm-cache.ps1 -BaseUrl "https://linkedin-profile-api.onrender.com" -ApiKey "your-key"

.EXAMPLE
    .\scripts\warm-cache.ps1 -BaseUrl "http://localhost:8080" -Profiles @(
        "https://www.linkedin.com/in/williamhgates",
        "https://www.linkedin.com/in/satyanadella")
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BaseUrl,

    [string] $ApiKey = "",

    [string[]] $Profiles = @(
        "https://www.linkedin.com/in/williamhgates",
        "https://www.linkedin.com/in/satyanadella",
        "https://www.linkedin.com/in/reidhoffman"
    ),

    # Generous by default. The service paces itself anyway; this keeps the script from
    # queueing behind its own gate.
    [int] $DelaySeconds = 12
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')

$headers = @{}
if ($ApiKey) { $headers["X-API-Key"] = $ApiKey }

Write-Host ""
Write-Host "Warming $BaseUrl with $($Profiles.Count) profile(s)" -ForegroundColor Cyan
Write-Host ""

# Wake the container first — free tiers sleep, and a cold start on the first real request
# looks like a timeout.
try {
    Write-Host "  waking service..." -NoNewline
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 90
    Write-Host " $($health.status)" -ForegroundColor Green
    if ($health.components.acquisition.details.sessionAvailable -eq $false) {
        Write-Warning "  No LinkedIn session is available. Expect PUBLIC_HTML or 422 responses."
    }
} catch {
    Write-Warning "  Health check failed: $($_.Exception.Message)"
}

Write-Host ""
$succeeded = 0
$index = 0

foreach ($profile in $Profiles) {
    $index++
    $encoded = [System.Uri]::EscapeDataString($profile)
    $uri = "$BaseUrl/api/v1/profiles?url=$encoded"
    $label = ($profile -split '/in/')[-1].TrimEnd('/')

    Write-Host ("  [{0}/{1}] {2}" -f $index, $Profiles.Count, $label) -NoNewline
    try {
        $response = Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 60
        $meta = $response.meta
        $colour = if ($meta.source -eq "VOYAGER") { "Green" } else { "Yellow" }
        Write-Host ("  -> {0}  completeness={1}  {2}ms" -f
            $meta.source, $meta.completeness, $meta.durationMs) -ForegroundColor $colour
        $succeeded++
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        Write-Host ("  -> FAILED  HTTP {0}" -f $status) -ForegroundColor Red
        if ($status -eq 401) { Write-Warning "    API key rejected. Check -ApiKey against API_KEYS." }
        if ($status -eq 429) { Write-Warning "    Rate limited. Raise -DelaySeconds and retry." }
    }

    if ($index -lt $Profiles.Count) { Start-Sleep -Seconds $DelaySeconds }
}

Write-Host ""
Write-Host "Warmed $succeeded of $($Profiles.Count)." -ForegroundColor Cyan
if ($succeeded -lt $Profiles.Count) {
    Write-Host "Check /actuator/health for session and breaker state before retrying." -ForegroundColor Yellow
}
Write-Host ""