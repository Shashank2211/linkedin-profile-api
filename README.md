# LinkedIn Profile API

A hosted API that resolves a LinkedIn profile URL into a stable, documented JSON contract —
with a layered acquisition chain, honest provenance metadata, and a parsing layer that is
fully testable without credentials.

Built for the Tross engineering hiring challenge. Java 21 / Spring Boot 3.3.

---

## Try it

```bash
curl -s "https://linkedin-profile-api-xli4.onrender.com/api/v1/profiles?url=https://www.linkedin.com/in/williamhgates" \
     -H "X-API-Key: $API_KEY" | jq
```

Interactive docs (OpenAPI 3.1 + Swagger UI): **`https://linkedin-profile-api-xli4.onrender.com/docs`**

> **What you will get.** The profile endpoint is live and serving real data via the
> public-page source, at `meta.completeness` around 0.8–0.9. The authenticated Voyager path
> is currently refused by LinkedIn at the session layer, so `meta.source` will read
> `PUBLIC_HTML` and `skills`, `certifications` and `languages` come back empty — JSON-LD does
> not carry them. That distinction is exactly what `meta.source` and `meta.completeness`
> exist to tell you, and the details are in [Known limitations](#upstream-access).
>
> `mvn test` on a clean clone exercises every parsing and mapping path against committed
> fixtures with no credentials and no network — including
> `mvn test -Dtest=ContractExampleTest`, which prints a complete response envelope.

---

## Approach

LinkedIn's official APIs cannot do this. `api.linkedin.com` returns only the *authenticated
user's own* lite profile; third-party profile data sits behind the Talent and Marketing
partner programmes, which are gated on a signed commercial agreement. There is no version of
this challenge that the documented API can satisfy — hence "reverse engineer".

So the service reads the same internal API the LinkedIn web app itself calls, and treats that
dependency as what it is: undocumented, versioned without notice, and actively defended.

**Three sources, one contract.** A `ProfileSource` chain tries each in order and stops at the
first that answers:

| # | Source | What it gives | When it runs |
|---|--------|---------------|--------------|
| 1 | **Voyager** — `/voyager/api/identity/dash/profiles`, authenticated | Everything the web app renders: about, full role history, education, skills, certifications, languages, images | Whenever a session cookie is alive and the breaker is closed |
| 2 | **Public HTML** — the logged-out `/in/{slug}` page, read via JSON-LD | Name, headline, location, image, current employers, schools, languages | When Voyager is unavailable, quarantined, or unconfigured |
| 3 | *(documented, not built)* Headless browser | DOM-level fallback, and a way to re-mint a dead cookie | Future work — see below |

Both implemented sources produce the **same `Profile` object**. A caller parses one shape;
`meta.source` and `meta.completeness` tell them how much they actually got.

**Acquisition and normalization never touch each other.** Sources return raw payloads;
mappers project raw payloads onto the domain model. When LinkedIn bumps a `decorationId`, one
adapter changes and nothing else does.

---

## API

### `GET /api/v1/profiles`

| Parameter | In | Required | Description |
|-----------|-----|----------|-------------|
| `url` | query | yes | Any spelling of a member profile URL. Full URLs, locale subdomains (`in.linkedin.com`), tracking parameters, a bare `linkedin.com/in/x`, or just `/in/x`. |
| `refresh` | query | no | `true` bypasses the freshness window and forces an upstream fetch. Spends rate budget — use sparingly. |
| `X-API-Key` | header | when configured | Also accepted as `Authorization: Bearer <key>`. |

The full response shape. `skills`, `certifications` and `languages` are populated only
by the Voyager source; see [Known limitations](#upstream-access) for what the live
deployment currently returns.

```json
{
  "meta": {
    "requestId": "0f7c1e2a-…",
    "fetchedAt": "2026-08-28T11:04:22Z",
    "source": "VOYAGER",
    "cached": false,
    "cacheAgeSeconds": 0,
    "stale": false,
    "completeness": 0.86,
    "durationMs": 812
  },
  "profile": {
    "publicIdentifier": "williamhgates",
    "profileUrl": "https://www.linkedin.com/in/williamhgates",
    "urn": "urn:li:fsd_profile:ACoAAA…",
    "name":     { "first": "Bill", "last": "Gates", "full": "Bill Gates" },
    "headline": "Co-chair, Bill & Melinda Gates Foundation",
    "about":    "…",
    "location": { "raw": "Seattle, Washington, United States", "city": null, "country": "us" },
    "industry": "Philanthropy",
    "profilePicture": {
      "url": "https://media.licdn.com/dms/image/…/800_800/…",
      "sizes": [ { "width": 800, "height": 800, "url": "…" }, … ]
    },
    "backgroundImage": null,
    "flags":  { "openToWork": null, "premium": false, "influencer": true },
    "counts": { "connections": null, "followers": 35000000 },
    "experience": [
      {
        "title": "Co-chair",
        "company": { "name": "Gates Foundation", "urn": "urn:li:fsd_company:…",
                     "logo": "…", "url": "…" },
        "employmentType": "FULL_TIME",
        "location": "Seattle, Washington",
        "locationType": "HYBRID",
        "startDate": "2000-01",
        "endDate": null,
        "current": true,
        "description": "…",
        "skills": []
      }
    ],
    "education":      [ { "school": "…", "degree": "…", "fieldOfStudy": "…",
                          "startDate": "1973", "endDate": "1975", "grade": null,
                          "activities": null, "description": null } ],
    "skills":         [ { "name": "Philanthropy", "endorsements": 42 } ],
    "certifications": [ { "name": "…", "authority": "…", "credentialId": null,
                          "url": "…", "issuedDate": "2025-02", "expirationDate": null } ],
    "languages":      [ { "name": "English", "proficiency": "NATIVE_OR_BILINGUAL" } ]
  }
}
```

### Contract rules

These are guarantees, not conventions:

- **Every key is always present.** Missing scalars are `null`; missing collections are `[]`.
  A client never branches on key existence.
- **`null` means "we could not read this". `[]` means "there is none."** They are different
  facts and the schema keeps them apart — `meta.completeness` is what tells you which
  situation you are in.
- **Every date is an ISO-8601 partial** — `"2024"`, `"2024-06"`. LinkedIn almost never sends
  a day, so we never pad to the first of the month. A padded date silently corrupts any
  tenure calculation downstream.
- **Enum-shaped values are `SCREAMING_SNAKE` strings, not closed Java enums.** A new
  employment type appearing upstream must not be able to fail a request.
- **URNs are exposed, not hidden.** They are the only stable identifier LinkedIn gives out.

### `DELETE /api/v1/profiles/{publicIdentifier}/cache`

Evicts one member from the cache. Profile data is personal data; a service that holds it
should be able to drop a named individual without a redeploy. Returns `204` whether or not a
copy was held.

### Errors

Every failure returns the same body: `{ "error": { "code", "message", "requestId", "timestamp" } }`.

| Status | Code | Meaning |
|--------|------|---------|
| 400 | `INVALID_PROFILE_URL` | Not a parseable member profile URL — includes company, school and feed URLs |
| 401 | `UNAUTHORIZED` | Missing or invalid API key |
| 404 | `PROFILE_NOT_FOUND` | The identifier resolves to no member |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for the path. Always with `Allow` |
| 422 | `PROFILE_NOT_ACCESSIBLE` | The member exists but is private, or every source hit an auth wall |
| 429 | `RATE_LIMITED` | Caller exceeded their bucket. Always with `Retry-After` |
| 503 | `UPSTREAM_UNAVAILABLE` | Every source failed or is circuit-broken. With `Retry-After` |
| 504 | `UPSTREAM_TIMEOUT` | Request budget exhausted before any source answered |

The 422 is the one worth pointing at. A private profile is **not** a server error: the request
was well-formed and understood, the data simply is not visible from where we stand. Returning
500 there tells the caller to retry, which cannot help.

---

## Architecture

```
                    ┌────────────────────────────────────────────────┐
  GET /profiles ───►│ 1 Edge      api-key · rate limit · request id  │
                    ├────────────────────────────────────────────────┤
                    │ 2 Validate  URL → slug · host allowlist        │  ← SSRF boundary
                    ├────────────────────────────────────────────────┤
                    │ 3 Cache     Caffeine · fresh TTL / stale TTL   │──► hit ──► response
                    ├────────────────────────────────────────────────┤
                    │ 4 Chain     one shared wall-clock budget       │
                    │             ├─ VoyagerProfileSource   (breaker)│
                    │             └─ PublicHtmlProfileSource(breaker)│
                    ├────────────────────────────────────────────────┤
                    │ 5 Normalize UrnGraph → section mappers         │
                    ├────────────────────────────────────────────────┤
                    │ 6 Score     completeness · provenance          │
                    ├────────────────────────────────────────────────┤
                    │ 7 Envelope  meta + profile · cache write-back  │
                    └────────────────────────────────────────────────┘
```

```
com.sahil.linkedinapi
├─ api/           controller · DTOs · @RestControllerAdvice · OpenAPI
├─ domain/        Profile, Experience, Education…  (immutable records)
├─ application/   ProfileService · CompletenessScorer
├─ acquisition/   ProfileSource · ProfileSourceChain · SourceBreaker · PaceGate
│  ├─ voyager/    VoyagerClient · VoyagerProfileSource
│  └─ publichtml/ PublicHtmlProfileSource · JsonLdProfileMapper
├─ session/       SessionManager · LinkedInSession (health, cooldown, quarantine)
├─ normalize/     UrnGraph · VoyagerProfileMapper · ImageMapper   ← the interesting code
├─ cache/         ProfileCache (Caffeine, two TTLs)
├─ url/           ProfileUrlParser
└─ config/        properties · filters · http client · health indicator
```

### The normalization problem

This is the part worth reading first: **`normalize/UrnGraph.java`**.

Voyager answers with `application/vnd.linkedin.normalized+json+2.1`, which is not a document.
Every entity is flattened into one `included[]` array keyed by `entityUrn`, and anything that
would have been a nested object is replaced by a URN *pointer* on a key prefixed with `*`:

```json
{ "data":     { "*elements": ["urn:li:fsd_profile:ACoAAB…"] },
  "included": [ { "entityUrn": "urn:li:fsd_profile:ACoAAB…",
                  "firstName": "…",
                  "*profilePositionGroups": ["urn:li:fsd_profilePositionGroup:(…)"] },
                { "entityUrn": "urn:li:fsd_profilePositionGroup:(…)", … } ] }
```

Your positions are not inside your profile; they are three hops away. `UrnGraph` does the two
generic steps — index by URN, then resolve `*`-pointers depth-first — so every mapper
downstream reads an ordinary tree.

Three things about it that are not obvious until you hit them:

- **Cycles are real.** A company points at a position that points back at the company. The
  visited set is not defensive coding; without it the first real profile you try overflows the
  stack.
- **An unresolvable pointer degrades to its URN string** rather than disappearing. A company
  we could not expand is still a company we can name by id.
- **Position groups hide promotions.** LinkedIn nests roles inside a per-company group so the
  UI can render three promotions as one card. Reading only the group level gives you one entry
  per employer and silently loses every promotion — `flattensPositionGroups` in
  `VoyagerProfileMapperTest` exists specifically to catch that regression.

### Reliability

- **One budget for the whole chain**, not a timeout per source — independent timeouts stack
  into a thirty-second failure.
- **Circuit breaker per source.** When Voyager opens, requests fall through to the public page
  and `meta.source` says so. Degraded beats down.
- **Never retry an auth failure.** `401`, `403`, `429`, `999` and checkpoint redirects
  quarantine the session immediately. Retrying those is how a temporary block becomes a
  permanent one.
- **Redirects are not followed.** A `302` to `/authwall` is the clearest signal LinkedIn gives
  that a cookie is dead; following it turns that signal into a 200 with a login page in the
  body, and the mapper then reports an empty profile instead of a dead session.
- **Stale-while-revalidate.** Between the fresh and stale TTLs, an upstream failure serves the
  cached copy with `meta.stale: true` and a real `cacheAgeSeconds` rather than a 503.
- **Deliberate slowness outbound.** `PaceGate` enforces a minimum gap plus jitter between
  LinkedIn calls. The cache is what makes that acceptable.

### Why these Java choices

- **Virtual threads** (`spring.threads.virtual.enabled=true`) — the workload is almost pure
  blocking I/O against a slow upstream. This gives WebFlux-grade concurrency while keeping
  straight-line imperative code that is debuggable at 2am.
- **JDK `HttpClient`, not `RestClient`** — we need exact control over cookie and CSRF headers
  and, critically, `followRedirects(NEVER)`.
- **Jackson `JsonNode`, not generated POJOs** — the Voyager payload is a heterogeneous graph
  with unstable types. Binding it to classes buys nothing and breaks on every added field.
- **A hand-rolled `SourceBreaker` instead of Resilience4j** — forty lines, unit-tested here,
  and one fewer dependency between a reviewer and a first-time green build. Resilience4j is
  the right swap once this needs bulkheads and metrics.

---

## Quick start

```bash
git clone <this repo> && cd linkedin-profile-api

# Tests need nothing: no cookies, no network.
mvn test

# Run it. Without credentials, only the public-HTML source is active.
mvn spring-boot:run

# With credentials:
cp .env.example .env      # fill in LINKEDIN_LI_AT and LINKEDIN_JSESSIONID
set -a && . ./.env && set +a
mvn spring-boot:run
```

**Getting the two cookies.** Log in to LinkedIn in a browser, then
DevTools → Application → Cookies → `https://www.linkedin.com`:

- `li_at` — the long opaque session token.
- `JSESSIONID` — looks like `"ajax:1234567890123456789"`. Paste it with or without the
  surrounding quotes; the service normalizes both and derives the `csrf-token` header from it.

Use a secondary account you would not mind losing, and keep the request rate low.

### Configuration

Every value is an environment variable; nothing needs a rebuild.

| Variable | Default | Notes |
|----------|---------|-------|
| `LINKEDIN_LI_AT` | — | Session cookie. Comma-separate for a pool. |
| `LINKEDIN_JSESSIONID` | — | Same, and in the same order. |
| `API_KEYS` | *(empty)* | Comma-separated. **Empty disables auth** and logs a loud warning. |
| `VOYAGER_DECORATION_ID` | `…FullProfileWithEntities-93` | Bump this in the dashboard when field mapping starts returning nulls — no redeploy. |
| `CACHE_FRESH_TTL` | `PT6H` | Serve from cache without asking LinkedIn. |
| `CACHE_STALE_TTL` | `PT24H` | Usable-on-failure window. |
| `RATE_LIMIT_RPM` | `30` | Inbound, per API key or client address. |
| `OUTBOUND_PROXY` | *(none)* | `http://user:pass@host:port` for the LinkedIn calls. |
| `PORT` | `8080` | |

---

## Testing

```bash
mvn verify
```

**The whole suite runs with no credentials and no network access.** Recorded payloads live in
`src/test/resources/fixtures/` and every parsing and mapping path is exercised against them.

That is a deliberate design goal, not a convenience: the cookie that works while I build this
may well be dead by the time anyone reviews it, and the review should still be able to verify
that the logic is correct. `mvn test` on a clean clone is the proof.

| Layer | What it pins |
|-------|--------------|
| `UrnGraphTest` | Pointer resolution, cycles, dangling references, envelope-shape fallbacks |
| `VoyagerProfileMapperTest` | Every section, position-group flattening, ISO partial dates, image variant selection, tolerance of a near-empty payload |
| `ProfileSourceChainTest` | Fallback order, 404 short-circuit, breaker opening and closing, and the 422-vs-503 decision |
| `ProfileServiceTest` | Cache hit inside the freshness window, `refresh=true` bypass, stale-while-revalidate, eviction |
| `ProfileControllerTest` | One status per failure mode, the single error shape, and that an internal message never reaches the client |
| `ProfileUrlParserTest` | URL normalization, unicode identifiers, and the SSRF boundary |
| `CompletenessScorerTest`, `SourceBreakerTest` | Scoring weights and breaker transitions |
| `ApplicationContextTest` | The app boots and stays healthy with **no credentials at all** |

### Capturing real fixtures

The committed fixture is **synthetic** — it mirrors the real response shape closely enough to
exercise every code path, but it contains no real member data. Pinning the mappers against a
genuine payload is the highest-value work left, and
**[`docs/capturing-fixtures.md`](docs/capturing-fixtures.md)** walks through it: which request
to copy out of DevTools, which three profiles to capture and why they differ, and which field
paths are guesses worth verifying first.

Three tools support that loop. `scripts/capture-voyager.ps1` replays the exact request
`VoyagerClient` makes — rather than copying from DevTools, which now captures a *different*
request, since the web app has moved to `/voyager/api/graphql` while this service still calls
the REST-li endpoint. `scripts/check-capture.ps1` then verdicts a raw capture before you
spend a redaction cycle on it, and `RealFixtureMappingReportTest` maps any `voyager-real*.json`
and prints which field paths resolved, and for the ones that did not, how far each path got
and which keys were actually present where it stopped. That test skips when no real fixture
is committed, so a clean clone still goes green.

A redaction tool is included — single file, JDK only, no build step:

```bash
java tools/FixtureRedactor.java fixtures/raw/captured.json src/test/resources/fixtures/voyager-real.json
```

It strips names, free text, locations, organisations, credential numbers and media URLs while
keeping every key, the pointer structure and the array shapes — and it remaps member ids
*consistently*, so the URN graph still resolves afterwards and the fixture still tests
something. It ends with a residual scan for cookies, CSRF tokens, email addresses and
unmapped ids, and exits non-zero if it finds any.

`src/test/resources/fixtures/raw/` and `*.har` are gitignored, so unredacted captures cannot
be committed by accident.

---

## Deployment

Any Docker host works. `render.yaml` is included for one-click Render deploys; Fly.io and
Railway need the same three things — a Docker build, port 8080, and `/actuator/health` as the
probe.

```bash
docker build -t linkedin-profile-api .
docker run -p 8080:8080 \
  -e LINKEDIN_LI_AT="$LINKEDIN_LI_AT" \
  -e LINKEDIN_JSESSIONID="$LINKEDIN_JSESSIONID" \
  -e API_KEYS="some-key" \
  linkedin-profile-api
```

The image is a multi-stage build onto `eclipse-temurin:21-jre-alpine`, running as a non-root
user, with Spring Boot layered-jar extraction so a redeploy ships a few hundred KB rather than
the whole fat jar.

**Free tiers sleep.** The first request after an idle period pays a cold start of a few
seconds. Either keep it warm or expect it.

Step-by-step deployment, the secret-hygiene checks to run before the first commit, and a
requirements checklist are in
**[`docs/deploy-and-submit.md`](docs/deploy-and-submit.md)**. After deploying, prime the cache
so the first real request is served from memory rather than discovering a throttled session:

```powershell
.\scripts\warm-cache.ps1 -BaseUrl "https://linkedin-profile-api-xli4.onrender.com" -ApiKey "<your key>"
```

### Observability

`GET /actuator/health` reports session availability, per-source breaker state, cache size and
hit rate — no secrets. `/actuator/metrics` carries the usual Micrometer set. Every log line
carries the `requestId` that the caller also received in `X-Request-Id`.

---

## Security

- **The caller-supplied URL never reaches an outbound request.** `ProfileUrlParser` reduces it
  to a validated slug, and every fetch composes its own URL from a hardcoded host plus that
  slug. A caller cannot steer the service at an internal address or a different host. This is
  the single most important line of defence in the codebase and it is unit-tested.
- **Secrets only ever come from the environment.** `.env` is gitignored, `.env.example` is
  committed with empty values, and `*.har` is gitignored so a captured session cannot be
  committed with a browser dump.
- **CI runs gitleaks over the full history** and fails the build on a hit — the challenge asks
  for no credentials in the repository, and this makes that checkable rather than claimed.
- **Cookies are never logged.** They live in memory on `LinkedInSession`, are written only
  into an outbound header, and no code path prints them. Internal exception messages are not
  echoed to clients, since they can carry upstream URLs.
- **API keys are compared in constant time** and used as a hashed map key for rate limiting,
  never stored raw.
- **No persistence.** Nothing is written to disk or to a database. Profile data lives in a
  bounded in-memory cache with a TTL, and there is an endpoint to purge a named individual.

---

## Legal note

Worth stating plainly, because the challenge asks for known limitations and this is the
largest one.

LinkedIn's official APIs do not expose third-party profile data, which is why the brief asks
for reverse engineering. **Automated access to the internal Voyager API is contrary to
LinkedIn's User Agreement.** The *hiQ Labs v. LinkedIn* line of cases narrowed the CFAA's
reach over **publicly accessible** data, but LinkedIn ultimately obtained a permanent
injunction in 2022 on contract and unfair-competition grounds — aimed specifically at
logged-in scraping via fabricated accounts. The distinction that matters is public versus
authenticated, and it cuts against the authenticated path.

What that means for how this is built:

- No fabricated accounts. Own credentials only.
- Conservative request rates with enforced pacing, and aggressive caching so the upstream is
  touched as rarely as possible.
- No bulk harvesting, no crawling of connection graphs, no storage or resale of collected data.
- Nothing persisted beyond a bounded in-memory TTL, plus a cache-purge endpoint — profile data
  is personal data under GDPR and India's DPDP Act.

**This is a technical demonstration built for a hiring process, not a production or commercial
service.** I would not deploy it as one without a legal review and a licensed data source.

---

## Known limitations

### Upstream access

<a name="upstream-access"></a>

**The public-page source works; the authenticated Voyager source does not.** Every profile
served by the live deployment comes from the logged-out page via JSON-LD.

- **Voyager is refused at the session layer.** It answers `302` back to the *same* URL with
  `Set-Cookie: li_at=delete me; Expires=Thu, 01-Jan-1970; Max-Age=0` — LinkedIn echoing the
  session token back with instructions to discard it. That survives a freshly minted cookie
  from a full browser login, and repeats across a bounded retry. No request has succeeded on
  this path.
- **The consequence is visible in the data, not hidden.** `meta.source` reads `PUBLIC_HTML`
  and `meta.completeness` lands around 0.8–0.9. Name, headline, location, industry, about,
  roles, schools and images come through; `skills`, `certifications` and `languages` are `[]`
  because JSON-LD does not publish them. A caller reading `meta` knows which situation it is
  in — which is the entire reason that block exists.
- **The web app has moved to `/voyager/api/graphql`.** Captured responses carry
  `meta.microSchema.isGraphQL: true` and nest their collection at
  `data.data.<queryName>."*elements"`. The REST-li endpoint this client calls is no longer the
  one the browser uses. `UrnGraph` handles both envelope shapes; the entity projections inside
  them differ, which is why real fixtures are the highest-value work remaining.

Restoring the authenticated path is not a code change. It needs an acquisition route this
project deliberately scoped out: a headless browser presenting a real browser fingerprint,
able to re-mint its own session, behind residential egress. `OUTBOUND_PROXY` is already wired
for the second half of that.

### Everything else

- **Data depth depends on the viewing account.** 2nd- and 3rd-degree profiles return
  materially less than 1st-degree ones, so the same request from two different sessions
  returns different completeness. This is a property of LinkedIn, not a bug here, and it is
  why `meta.completeness` exists.
- **`decorationId` versions change without notice**, which breaks field mapping. Mitigated
  three ways: the value is an environment variable, the mappers try several known paths per
  field, and the fallback chain degrades rather than failing.
- **Image URLs are signed and expire**, typically within hours. Consumers must fetch promptly
  or rehost.
- **Rate limits and account risk** make a single-session deployment unsuitable for real
  volume. Horizontal scale needs a session pool with per-session residential proxies, which is
  out of scope here.
- **Not fetched:** recommendations, volunteer work, projects, publications, honours, full
  endorsement detail, and exact connection counts. Each needs additional Voyager calls; the
  domain model has room for them.
- **`location.city` is usually null.** LinkedIn returns one formatted location string; we do
  not split it on a comma and guess, because a confidently wrong city is worse than an
  honest null.
- **The public-HTML fallback is thin and getting thinner.** LinkedIn shows anonymous visitors
  less every year and auth-walls aggressively by region.
- **Name splitting is Latin-centric on the public-HTML path.** JSON-LD gives one `name`
  string, and the fallback mapper splits on the last space — wrong for many naming
  conventions. `full` is always populated, so a caller who cares can ignore the split. The
  Voyager path gets `firstName` / `lastName` directly and is unaffected.
  *(Profile **identifiers** in non-Latin scripts are handled properly — see
  `ProfileUrlParser`, whose pattern allows combining marks so Indic, Arabic and Thai slugs
  are not rejected.)*
- **No freshness guarantee** beyond the configured TTL — stated honestly in `meta` rather than
  hidden.

## Future work

In rough order of value: a Playwright source that both provides a DOM-level fallback and
re-mints dead cookies; a session pool with per-session proxies and least-usage selection; a
Redis L2 cache; an async `POST /api/v1/jobs` endpoint with webhook callback for bulk use;
Resilience4j in place of the hand-rolled breaker once bulkheads are needed; and the remaining
profile sections above.
