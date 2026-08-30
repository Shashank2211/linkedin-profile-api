<#
.SYNOPSIS
    Captures a raw Voyager payload by replaying the exact request VoyagerClient makes.

.DESCRIPTION
    Copying a response out of DevTools captures what the BROWSER asked for. That is no
    longer the same thing as what this service asks for: the LinkedIn web app has moved to
    /voyager/api/graphql with component-shaped responses, while VoyagerClient still calls
    the REST-li endpoint /voyager/api/identity/dash/profiles with a decorationId and an
    "accept: application/vnd.linkedin.normalized+json+2.1" header.

    A fixture is only worth having if it is the payload the mapper will actually parse, so
    this builds the same URL and the same headers VoyagerClient builds and writes the raw
    response to disk. It doubles as the live smoke test: if the REST-li endpoint has been
    retired, you find out here, in one request, rather than after a deploy.

    Redirects are NOT followed, for the reason the README gives: a 302 to /authwall is the
    clearest signal a cookie is dead, and following it turns that into a 200 containing a
    login page.

    ONE REQUEST PER RUN. Do not loop this. A throttled session costs a 15-minute cooldown.

.PARAMETER Slug
    The public identifier, e.g. "williamhgates" from linkedin.com/in/williamhgates.

.PARAMETER OutFile
    Where to write the raw response. Defaults to src\test\resources\fixtures\raw\<slug>.json,
    which is gitignored.

.EXAMPLE
    .\scripts\capture-voyager.ps1 -Slug williamhgates

.EXAMPLE
    .\scripts\capture-voyager.ps1 -Slug someone -EnvFile .env
#>
[CmdletBinding()]
param(
    # Not mandatory, because -CheckSession asks about the session rather than a member.
    [string] $Slug,

    [string] $OutFile,

    [string] $EnvFile = ".env",

    [string] $LiAt,

    [string] $JsessionId,

    [string] $DecorationId = "com.linkedin.voyager.dash.deco.identity.profile.FullProfileWithEntities-93",

    # Show the identifier, URL and output path that would be used, then stop without
    # calling LinkedIn. Worth having when every real request costs rate budget.
    [switch] $DryRun,

    # Call /voyager/api/me instead of the profile endpoint.
    #
    # A 403 on the profile endpoint has two very different causes and the same symptom: the
    # session cookie is dead, or the cookie is fine and that particular endpoint is refusing
    # us. /me is the cheapest question that separates them - it is the endpoint the web app
    # uses to identify the logged-in member, so a 200 here means the cookie is unambiguously
    # alive and the problem is the profile endpoint itself.
    [switch] $CheckSession
)

$ErrorActionPreference = "Stop"

# --- slug normalization -------------------------------------------------------------------
# ProfileUrlParser accepts every spelling of a profile URL, so this should too - pasting the
# URL straight from the address bar is the obvious thing to do. Reduce whatever was given to
# the bare public identifier before it reaches a URL or a filename.
if (-not $Slug -and -not $CheckSession) {
    throw "-Slug is required (a public identifier or a profile URL). Use -CheckSession to test the cookie instead."
}
if (-not $Slug) { $Slug = "session-check" }

$Slug = $Slug.Trim()
if ($Slug -match '/in/([^/?#]+)') {
    $Slug = $Matches[1]
} else {
    # No /in/ segment: drop any scheme, query and fragment, then take the last path segment.
    $Slug = $Slug -replace '^[a-zA-Z]+://', ''
    $Slug = ($Slug -split '[?#]')[0]
    $Slug = ($Slug.TrimEnd('/') -split '/')[-1]
}
$Slug = [System.Uri]::UnescapeDataString($Slug)

if (-not $Slug) {
    throw "Could not read a profile identifier from -Slug."
}
# A slug that still carries path or drive punctuation would produce a nonsense output path,
# which is how this failed the first time. Catch it here with a message that says why.
if ($Slug -match '[\\/:*?"<>|]') {
    throw "'$Slug' does not look like a public identifier. Pass the identifier (e.g. 'williamhgates') or a profile URL."
}
Write-Verbose "Resolved identifier: $Slug"

# --- credentials ------------------------------------------------------------------------
# Read from .env unless passed explicitly. Never echoed: the only place these go is the
# outbound header.
if ((-not $LiAt -or -not $JsessionId) -and (Test-Path $EnvFile)) {
    foreach ($line in Get-Content $EnvFile) {
        if ($line -match '^\s*LINKEDIN_LI_AT\s*=\s*(.+)\s*$'      -and -not $LiAt)       { $LiAt = $Matches[1].Trim() }
        if ($line -match '^\s*LINKEDIN_JSESSIONID\s*=\s*(.+)\s*$' -and -not $JsessionId) { $JsessionId = $Matches[1].Trim() }
        if ($line -match '^\s*VOYAGER_DECORATION_ID\s*=\s*(.+)\s*$') {
            $fromEnv = $Matches[1].Trim()
            if ($fromEnv) { $DecorationId = $fromEnv }
        }
    }
}

if ($DryRun) {
    # A dry run checks the identifier, URL and output path, so absent credentials must not
    # stop it. These placeholders never leave this process - a dry run makes no request.
    if (-not $LiAt)       { $LiAt = "(dry-run)" }
    if (-not $JsessionId) { $JsessionId = "(dry-run)" }
} else {
    if (-not (Test-Path $EnvFile) -and (-not $LiAt -or -not $JsessionId)) {
        throw "No $EnvFile found. Create it from .env.example, or pass -LiAt and -JsessionId."
    }
    if (-not $LiAt)       { throw "LINKEDIN_LI_AT is empty. Fill it in $EnvFile." }
    if (-not $JsessionId) { throw "LINKEDIN_JSESSIONID is empty. Fill it in $EnvFile." }
}

# Mirrors LinkedInSession: the csrf-token header is JSESSIONID with its quotes stripped.
$csrf = $JsessionId.Trim().Trim('"')

if (-not $OutFile) {
    $OutFile = Join-Path "src\test\resources\fixtures\raw" "$Slug.json"
}
$outDir = Split-Path -Parent $OutFile
if ($outDir -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}

# --- the request ------------------------------------------------------------------------
# Same URL and headers VoyagerClient builds. Keep these in step with that class.
if ($CheckSession) {
    $uri = "https://www.linkedin.com/voyager/api/me"
} else {
    $uri = "https://www.linkedin.com/voyager/api/identity/dash/profiles" +
           "?q=memberIdentity" +
           "&memberIdentity=$([System.Uri]::EscapeDataString($Slug))" +
           "&decorationId=$([System.Uri]::EscapeDataString($DecorationId))"
}

# NOTE: the cookie is deliberately NOT in this hashtable.
#
# Invoke-WebRequest silently DISCARDS a "cookie" key passed through -Headers - no error, no
# warning, the request simply goes out unauthenticated and LinkedIn answers 403 with an empty
# body, which looks exactly like a dead session or an endpoint block. Cookies have to go
# through a WebRequestSession. This cost an afternoon; leave it alone.
$headers = @{
    "accept"                    = "application/vnd.linkedin.normalized+json+2.1"
    "x-restli-protocol-version" = "2.0.0"
    "csrf-token"                = $csrf
    "x-li-lang"                 = "en_US"
    "accept-language"           = "en-US,en;q=0.9"
    "referer"                   = "https://www.linkedin.com/in/$Slug/"
}

$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$webSession.Cookies.Add((New-Object System.Net.Cookie("li_at", $LiAt, "/", ".linkedin.com")))
# JSESSIONID is sent WITH its surrounding quotes; the csrf-token header carries the same
# value WITHOUT them. That asymmetry is LinkedIn's, and LinkedInSession reproduces it too.
$webSession.Cookies.Add((New-Object System.Net.Cookie("JSESSIONID", "`"$csrf`"", "/", ".linkedin.com")))
$userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

if ($DryRun) {
    Write-Host ""
    Write-Host "DRY RUN - no request made" -ForegroundColor Yellow
    if ($CheckSession) {
        Write-Host "  mode       : session check (writes nothing)"
    } else {
        Write-Host "  identifier : $Slug"
    }
    Write-Host "  url        : $uri"
    if (-not $CheckSession) { Write-Host "  out        : $OutFile" }
    Write-Host ""
    exit 0
}

Write-Host ""
Write-Host "Requesting profile '$Slug'" -ForegroundColor Cyan
Write-Host "  decorationId: $DecorationId"
Write-Host "  (one request; do not re-run in a loop)"
Write-Host ""

$status = $null
$errorHeaders = $null
$body = $null
$location = $null

# HttpWebRequest rather than Invoke-WebRequest.
#
# With -MaximumRedirection 0, Invoke-WebRequest throws an InvalidOperationException that
# carries no Response object, so the redirect target - the single most useful thing in a
# LinkedIn failure - is unreachable. HttpWebRequest with AllowAutoRedirect = $false hands
# back the 3xx as an ordinary response, Location header included. This mirrors what the Java
# client does with Redirect.NEVER, and for the same reason: a redirect to /authwall is a
# diagnosis, and following it would replace that diagnosis with a login page rendered as 200.
function Invoke-VoyagerRequest {
    param($Uri, $Cookies, $Headers, $UserAgent, $Referer)

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = "GET"
    $request.AllowAutoRedirect = $false
    # A shared CookieContainer means Set-Cookie from a response is stored automatically and
    # replayed on the next call - which is exactly what the datacenter handshake needs.
    $request.CookieContainer = $Cookies
    $request.Timeout = 30000
    # These are properties on HttpWebRequest, not free-form headers; Headers.Add throws.
    $request.Accept = "application/vnd.linkedin.normalized+json+2.1"
    $request.UserAgent = $UserAgent
    if ($Referer) { $request.Referer = $Referer }
    foreach ($name in @("x-restli-protocol-version", "csrf-token", "x-li-lang", "accept-language")) {
        if ($Headers.ContainsKey($name)) { $request.Headers.Add($name, $Headers[$name]) }
    }

    $resp = $null
    try {
        $resp = $request.GetResponse()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
    }

    $text = $null
    try {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $text = $reader.ReadToEnd()
    } catch { }

    $result = [pscustomobject]@{
        Status    = [int] $resp.StatusCode
        Location  = $resp.Headers["Location"]
        SetCookie = $resp.Headers["Set-Cookie"]
        Headers   = $resp.Headers
        Body      = $text
    }
    $resp.Close()
    return $result
}

$referer = $null
if (-not $CheckSession) { $referer = "https://www.linkedin.com/in/$Slug/" }

$result = Invoke-VoyagerRequest -Uri $uri -Cookies $webSession.Cookies -Headers $headers `
                               -UserAgent $userAgent -Referer $referer

# LinkedIn's datacenter handshake.
#
# The edge answers a first call with 302 back to the SAME url, plus a Set-Cookie carrying
# "lidc" - the cookie that pins a session to one datacenter. This is not an auth wall; it is
# the edge saying "retry, now that you know where you belong". A browser completes it without
# the user ever seeing it. Retried EXACTLY ONCE, and only when the target is the same url, so
# this can never become a redirect loop against LinkedIn.
# LinkedIn's datacenter handshake.
#
# The edge answers with 302 back to the SAME url plus a Set-Cookie, meaning "retry, now that
# you carry these". A browser completes this without the user seeing it. The shared
# CookieContainer stores what comes back, so each round carries strictly more than the last.
#
# Bounded to three rounds and only ever to the SAME url, so this can never become a redirect
# loop against LinkedIn. Anything still redirecting after three rounds is not a handshake.
$handshakeRounds = 0
while ($result.Status -in 301, 302, 303, 307, 308 -and
       $result.Location -and
       $result.Location.TrimEnd('/') -eq $uri.TrimEnd('/') -and
       $handshakeRounds -lt 3) {

    $handshakeRounds++

    # A Set-Cookie that expires li_at in 1970 is LinkedIn deleting the token we just sent -
    # a rejected session stated as plainly as the protocol allows.
    # LinkedIn deletes a rejected token by sending it back with the literal value
    # "delete me" and a 1970 expiry. A single regex is the wrong tool here: cookie dates
    # contain commas ("Expires=Thu, 01-Jan-1970"), so any comma-bounded pattern silently
    # fails to match the very thing it was written for.
    $liAtRejected = ($result.SetCookie -match 'li_at=\s*delete me') -or
                    (($result.SetCookie -match 'li_at=') -and ($result.SetCookie -match 'Max-Age=0'))
    if ($liAtRejected) {
        Write-Host ""
        Write-Host "LinkedIn EXPIRED the li_at cookie we sent (Set-Cookie: li_at=...1970)." -ForegroundColor Red
        Write-Host "That is a rejected session token, not a routing problem." -ForegroundColor Red
        Write-Host ""
        Write-Host "Fix: log in to LinkedIn in the browser (a full login, not just opening a tab)," -ForegroundColor Yellow
        Write-Host "then re-copy BOTH cookies into .env - li_at rotates on every login, and the" -ForegroundColor Yellow
        Write-Host "csrf-token must come from the SAME session as li_at." -ForegroundColor Yellow
        Write-Host ""
        exit 1
    }

    Write-Host ("  handshake round {0}: 302 to the same url, retrying with what the edge set" -f $handshakeRounds) -ForegroundColor DarkGray
    if ($result.SetCookie) {
        Write-Host ("    Set-Cookie: {0}" -f $result.SetCookie) -ForegroundColor DarkGray
    }
    Write-Host ("    cookie jar now holds: {0}" -f
        (($webSession.Cookies.GetCookies($uri) | ForEach-Object { $_.Name }) -join ", ")) -ForegroundColor DarkGray

    $result = Invoke-VoyagerRequest -Uri $uri -Cookies $webSession.Cookies -Headers $headers `
                                    -UserAgent $userAgent -Referer $referer
}

$status = $result.Status
$errorHeaders = $result.Headers
$location = $result.Location
$body = $result.Body

Write-Host ("HTTP {0}" -f $status) -ForegroundColor $(if ($status -eq 200) { "Green" } else { "Red" })

# On a failure the body is the diagnosis. LinkedIn says which check failed - a CSRF
# mismatch, an expired session and a retired endpoint all return 403 and are three
# different fixes. Discarding this costs a whole extra request to recover.
if ($status -ne 200) {
    if ($errorHeaders) {
        foreach ($name in @("x-li-uuid", "x-li-proto", "www-authenticate", "content-type", "x-li-pop")) {
            $value = $errorHeaders[$name]
            if ($value) { Write-Host ("  {0}: {1}" -f $name, $value) -ForegroundColor DarkGray }
        }
    }
    if ($body) {
        $preview = $body.Trim()
        if ($preview.Length -gt 600) { $preview = $preview.Substring(0, 600) + " ...(truncated)" }
        Write-Host ""
        Write-Host "Response body:" -ForegroundColor Yellow
        Write-Host $preview
        Write-Host ""
    } else {
        Write-Host "  (empty response body)" -ForegroundColor DarkGray
    }
}

switch ($status) {
    200 { }
    { $_ -in 301, 302, 303, 307, 308 } {
        Write-Host ("Redirect -> {0}" -f $(if ($location) { $location } else { "(no Location header)" })) -ForegroundColor Yellow
        Write-Host ""
        # The target distinguishes three different problems that all present as a 3xx.
        if ($location -match "authwall|/login|uas/login") {
            Write-Host "That is the auth wall: the session cookie is not being accepted." -ForegroundColor Red
            Write-Host "Log in to LinkedIn again in the browser, then re-copy BOTH cookies into .env." -ForegroundColor Yellow
            Write-Host "li_at rotates on every fresh login, so a stale copy is the usual cause." -ForegroundColor Yellow
        } elseif ($location -match "checkpoint|challenge") {
            Write-Host "That is a CHECKPOINT - LinkedIn wants a human to verify the session." -ForegroundColor Red
            Write-Host "STOP making programmatic requests. Open LinkedIn in the browser, clear the" -ForegroundColor Yellow
            Write-Host "challenge, and leave the account alone for a while." -ForegroundColor Yellow
        } else {
            Write-Host "Unrecognised redirect target - paste this line to Claude." -ForegroundColor Yellow
        }
        exit 1
    }
    401 { Write-Host "Unauthorized - cookie rejected. Re-copy both cookies." -ForegroundColor Red; exit 1 }
    403 { Write-Host "Forbidden - cookie rejected or csrf-token mismatch." -ForegroundColor Red; exit 1 }
    404 {
        Write-Host "Not found. Either the slug is wrong, or this decorationId has been retired." -ForegroundColor Red
        Write-Host "If the slug is definitely right, the REST-li endpoint may be gone - tell Claude." -ForegroundColor Yellow
        exit 1
    }
    429 { Write-Host "Rate limited. STOP. Wait at least 15 minutes before any further calls." -ForegroundColor Red; exit 1 }
    999 { Write-Host "LinkedIn throttle (999). STOP. Wait at least 15 minutes." -ForegroundColor Red; exit 1 }
    default {
        Write-Host "Unexpected status. Not writing a fixture." -ForegroundColor Red
        exit 1
    }
}

if (-not $body) {
    Write-Host "Empty body despite a 200. Nothing to write." -ForegroundColor Red
    exit 1
}

if ($CheckSession) {
    # A session check is a question, not a capture. Never write /me to the fixtures
    # directory: it is a payload about the account holder, and nothing downstream reads it.
    Write-Host ""
    Write-Host "SESSION IS ALIVE." -ForegroundColor Green
    Write-Host "The cookie works, so a 403 on the profile endpoint is that endpoint refusing" -ForegroundColor Yellow
    Write-Host "us specifically - not a dead session. Tell Claude; this is a code question." -ForegroundColor Yellow
    Write-Host ""
    exit 0
}

Set-Content -Path $OutFile -Value $body -Encoding utf8
Write-Host ("Wrote {0} ({1:N0} bytes)" -f $OutFile, $body.Length) -ForegroundColor Green
Write-Host ""
Write-Host "Next: .\scripts\check-capture.ps1 $OutFile" -ForegroundColor Cyan
Write-Host ""
