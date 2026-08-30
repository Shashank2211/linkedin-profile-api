# Handoff prompt

Paste the block below into Claude Code from the project root
(`C:\Users\Shashank Kapadnis\Documents\linkedin-profile-api`). It is written to be
self-contained — it assumes no memory of how the project got here.

---

```
I'm finishing a hiring challenge for a company called Tross, due 31 August. The brief:
reverse-engineer LinkedIn's APIs and ship a publicly hosted HTTPS API that takes a LinkedIn
profile URL and returns the profile as structured JSON. Deliverables are a public GitHub
repo, a README covering setup / API docs / approach / known limitations, and no credentials
in the repo. The response schema is mine to design.

This project is already built and `mvn test` passes (84 tests, no credentials or network
needed). Read README.md first, then docs/deploy-and-submit.md — that file is the plan I'm
working through and it has a requirements checklist at the bottom.

Architecture in one paragraph so you don't have to reverse-engineer it: Java 21 / Spring Boot
3.3. A ProfileSource chain tries the authenticated Voyager internal API first
(/voyager/api/identity/dash/profiles, cookie + csrf-token derived from JSESSIONID), then the
logged-out public page via JSON-LD. Both map to the same Profile record. Voyager returns a
REST-li *normalized* payload — a flat included[] array joined by "*"-prefixed URN pointers —
and normalize/UrnGraph.java indexes and resolves that graph before the mappers see it. Every
response is a { meta, profile } envelope where meta carries source, cached, stale,
cacheAgeSeconds and completeness. Caffeine cache with two TTLs does stale-while-revalidate.
Per-source circuit breakers, a PaceGate that enforces a gap between outbound calls, and a
SessionManager that quarantines a session on 401/403/429/999/checkpoint instead of retrying.

WHAT'S LEFT, in priority order:

1. FIXTURES (highest value, blocks correctness). The committed fixture at
   src/test/resources/fixtures/voyager-profile.json is SYNTHETIC. It reproduces the response
   shape but the field paths in normalize/VoyagerProfileMapper.java are educated guesses.
   Follow docs/capturing-fixtures.md: I capture real Voyager responses from DevTools, run
   `java tools\FixtureRedactor.java raw.json out.json`, commit the redacted file, and then
   you help me write assertions against it and fix any field paths that come back null.
   Note: every read goes through Json.text(node, "path.a", "path.b", ...) — a list of
   candidate paths. When a field is null against a real payload the fix is almost always to
   ADD the real path to that list, not to restructure anything.

2. Live smoke test with my cookies in .env, then git init + push to a public GitHub repo.
   Before the first commit, run the secret-hygiene checks in docs/deploy-and-submit.md
   section 2 — .env, *.har and fixtures/raw/ must not be tracked.

3. Deploy to Render via the included Dockerfile and render.yaml, set env vars in the
   dashboard, verify HTTPS, confirm a request WITHOUT the API key returns 401.

4. Warm the cache (scripts/warm-cache.ps1), fill in the README's <your-deployment>
   placeholders, record a short demo GIF, walk the requirements checklist, submit.

CONSTRAINTS AND GOTCHAS, so you don't rediscover them:
- Never loop requests against LinkedIn. PaceGate is deliberately slow and a throttled
  session goes into a 15-minute cooldown. A handful of calls, then stop.
- Don't follow redirects on the LinkedIn calls. A 302 to /authwall is the signal that a
  cookie is dead; following it turns that into a 200 with a login page and the mapper then
  reports an empty profile instead of a dead session. HttpClientConfig sets Redirect.NEVER.
- A private profile is a 422, not a 500. Keep that distinction if you touch the error model.
- Watch for Unicode in identifiers. ProfileUrlParser's SLUG pattern allows \p{M} because
  combining marks are letters' equals in Indic scripts — a pattern of [\p{L}\p{N}] passes
  every ASCII test and rejects every Devanagari identifier. Don't "simplify" it.
- Never write \u in a Java comment. javac processes unicode escapes lexically, comments
  included, and an invalid one is a hard compile error.
- VOYAGER_DECORATION_ID is an env var precisely so a decoration bump can be fixed without a
  rebuild. If mapping suddenly returns nulls, check that before changing code.

Start by reading README.md and docs/deploy-and-submit.md, then tell me what you'd do first
and what you need from me.
```

---

## Shorter version, if you just want to resume mid-flow

```
Read README.md and docs/deploy-and-submit.md in this repo, then help me work through the
remaining steps in that file. `mvn test` currently passes. The immediate task is step 0:
capturing real Voyager fixtures and fixing any field paths in VoyagerProfileMapper that come
back null against them. Don't loop requests against LinkedIn — a throttled session costs 15
minutes.
```

---

## What is already done, for reference

| Area | State |
|------|-------|
| Domain model and response contract | Done — `domain/`, `api/dto/` |
| URN graph normalization | Done and tested, including cycles and dangling pointers |
| Voyager source | Done — headers, response classification, session quarantine |
| Public-HTML fallback | Done — JSON-LD reader mapping to the same contract |
| Source chain, breakers, pacing, budget | Done and tested |
| Cache with stale-while-revalidate | Done and tested |
| Error model, status mapping | Done and tested at the web layer |
| API-key auth, rate limiting, request ids | Done |
| SSRF boundary in the URL parser | Done and tested, Unicode included |
| OpenAPI / Swagger UI at `/docs` | Done |
| Health indicator | Done |
| Dockerfile, render.yaml, CI with gitleaks | Written, not yet exercised on a real deploy |
| Test suite runnable without credentials | Done — 84 tests |
| README, limitations, legal note | Done, minus the deployment URL |
| Fixture redaction tool | Done — `tools/FixtureRedactor.java` |
| Postman collection | Done — `docs/postman_collection.json` |
| **Real captured fixtures** | **Not done — highest value remaining** |
| **Live smoke test, GitHub, deploy, submit** | **Not done** |

Cut list, if time runs short: the Playwright browser source, a Redis L2 cache, an async job
endpoint, and the remaining profile sections (projects, publications, honours, volunteer).
All four are already written up in the README's future-work section, which is the right place
for them.
