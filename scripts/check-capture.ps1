<#
.SYNOPSIS
    Tells you whether a raw Voyager capture actually contains profile data.

.DESCRIPTION
    The LinkedIn profile page fires several requests against
    identityDashProfilesByMemberIdentity, and only one of them carries the profile. The
    others are cache-validation calls that return an entityUrn and a versionTag and nothing
    else - a couple of KB that look plausible in a file listing and are useless as a fixture.

    Finding that out after redacting and running the mapper costs a round trip. This costs a
    second. Run it on every capture before redacting.

.EXAMPLE
    .\scripts\check-capture.ps1 src\test\resources\fixtures\raw\me.json

.EXAMPLE
    .\scripts\check-capture.ps1 src\test\resources\fixtures\raw\*.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string[]] $Path
)

$ErrorActionPreference = "Stop"

# Fields that mean "this entity carries real profile content" rather than just an identity
# stub. firstName is the one UrnGraph's fallback scan keys on.
$ContentFields = @("firstName", "lastName", "headline", "summary", "publicIdentifier")

$anyBad = $false
$files = Get-ChildItem -Path $Path -File

if (-not $files) {
    Write-Host "No files matched." -ForegroundColor Red
    exit 2
}

foreach ($file in $files) {
    Write-Host ""
    Write-Host ("=" * 70)
    Write-Host $file.Name -ForegroundColor Cyan
    Write-Host ("=" * 70)
    Write-Host ("  size: {0:N0} bytes" -f $file.Length)

    $json = $null
    try {
        $json = Get-Content -Path $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Write-Host "  VERDICT: not valid JSON - $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "  You probably copied 'Copy as cURL' or the request headers instead of the response."
        $anyBad = $true
        continue
    }

    $isGraphQl = $false
    if ($json.meta -and $json.meta.microSchema) {
        $isGraphQl = [bool] $json.meta.microSchema.isGraphQL
    }
    if ($isGraphQl) { Write-Host "  envelope: GraphQL (/voyager/api/graphql)" }
    else            { Write-Host "  envelope: REST-li decoration" }

    $included = @($json.included)
    if (-not $json.PSObject.Properties.Name.Contains("included") -or $included.Count -eq 0) {
        Write-Host "  VERDICT: no included[] - this is not a profile response at all." -ForegroundColor Red
        Write-Host "  Look for the request whose response is LARGE (100s of KB), not the first match."
        $anyBad = $true
        continue
    }
    Write-Host ("  included[]: {0:N0} entities" -f $included.Count)

    # The distinguishing test. A content response has entities carrying names and headlines;
    # a cache-validation response has entities carrying only an id and a version tag.
    $withContent = 0
    foreach ($entity in $included) {
        $names = $entity.PSObject.Properties.Name
        foreach ($field in $ContentFields) {
            if ($names -contains $field) { $withContent++; break }
        }
    }
    Write-Host ("  entities carrying profile content: {0:N0}" -f $withContent)

    if ($withContent -eq 0) {
        Write-Host "  VERDICT: NO PROFILE DATA - do not redact this." -ForegroundColor Red

        # Name the specific case, because it is by far the most common one and the fix is
        # different from "you picked the wrong endpoint".
        $projected = @()
        if ($json.meta -and $json.meta.microSchema -and $json.meta.microSchema.types) {
            foreach ($type in $json.meta.microSchema.types.PSObject.Properties) {
                if ($type.Value.baseType -like "*identity.profile.Profile") {
                    $projected = $type.Value.fields.PSObject.Properties.Name
                }
            }
        }
        if ($projected.Count -gt 0 -and $projected.Count -le 4) {
            Write-Host ("  The server was asked for only: {0}" -f ($projected -join ", ")) -ForegroundColor Yellow
            Write-Host "  That is LinkedIn's cache-validation call ('is my copy stale?'), not the content call."
        }
        Write-Host "  Go back to the Network tab, sort by Size, and take the big one." -ForegroundColor Yellow
        $anyBad = $true
        continue
    }

    if ($file.Length -lt 20000) {
        Write-Host "  VERDICT: usable, but thin. Check it has the sections you expect." -ForegroundColor Yellow
    } else {
        Write-Host "  VERDICT: usable. Redact it." -ForegroundColor Green
    }
}

Write-Host ""
if ($anyBad) {
    Write-Host "At least one capture is not usable - see above." -ForegroundColor Red
    exit 1
}
Write-Host "All captures look usable." -ForegroundColor Green
Write-Host ""
