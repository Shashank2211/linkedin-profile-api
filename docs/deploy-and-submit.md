# Day 3: ship it

Deadline **31 August**. Work down this list in order — each step is cheap to undo, and the
expensive ones (deploy, GitHub) come after the ones that would force a rebuild.

Budget roughly: 1h fixtures, 30m repo, 45m deploy, 45m README polish and demo, then stop.
**Submit with hours to spare, not minutes.** A deploy that goes wrong at 23:00 on the 31st is
the only way to lose this from here.

---

## 0. Capture fixtures first — 1 hour

This is still the highest-value work left, and it belongs before deployment because it can
change the code. See **[capturing-fixtures.md](capturing-fixtures.md)** for the full walkthrough.

Short version: DevTools → Network → filter `voyager` → load your own profile, a 1st-degree
connection and a stranger → copy each response → redact → commit → assert.

```bat
java tools\FixtureRedactor.java fixtures\raw\me.json src\test\resources\fixtures\voyager-real-full.json
mvn test
```

When a field comes back null against a real payload, add the real path to the candidate list
in `VoyagerProfileMapper` — that is what those multi-path lookups are for. Note the real
`decorationId` from the request URL while you are in there.

> **Why this matters more than it looks.** The URL parser shipped with a pattern that passed
> thirteen ASCII tests and would have rejected every Devanagari identifier in production. The
> mapper's field paths are the same class of assumption, and only a real payload settles them.

---

## 1. Live smoke test — 15 minutes

```bat
copy .env.example .env
:: fill in LINKEDIN_LI_AT and LINKEDIN_JSESSIONID from DevTools > Application > Cookies
mvn spring-boot:run
```

In a second terminal:

```bat
curl -s "http://localhost:8080/api/v1/profiles?url=https://www.linkedin.com/in/williamhgates"
curl -s "http://localhost:8080/actuator/health"
```

Read `meta` first:

| What you see | What it means |
|--------------|---------------|
| `"source":"VOYAGER"`, completeness > 0.8 | The whole chain works. Move on. |
| `"source":"PUBLIC_HTML"` | The cookie is not being accepted. Check `/actuator/health` → `acquisition.sessions` before touching code. |
| `"source":"VOYAGER"`, completeness < 0.5 | Payload arrived but mapping is missing fields. Back to step 0. |
| 422 | Auth wall everywhere, or the profile is private. Try a different profile before assuming a bug. |

**Do not loop this.** A handful of calls, then stop — `PaceGate` is deliberately slow and a
quarantined session takes 15 minutes to come back.

Open **http://localhost:8080/docs** and confirm Swagger UI renders. That page is a large part
of what a reviewer will judge.

---

## 2. Git and GitHub — 30 minutes

The challenge says *keep all credentials and secrets out of the repository*. Verify it rather
than assume it.

```bat
cd "C:\Users\Shashank Kapadnis\Documents\linkedin-profile-api"
git init
git add .
git status
```

Before the first commit, confirm nothing sensitive is staged:

```bat
git ls-files | findstr /i ".env"
git ls-files | findstr /i ".har"
git ls-files | findstr /i "fixtures/raw"
```

All three must print **nothing**. Then grep the staged content itself:

```bat
git grep -i "li_at" -- ":!*.md" ":!.env.example"
git grep -i "ajax:" 
git grep -i "AQEDA"
```

`li_at` should appear only in documentation and `.env.example`. `ajax:` and `AQEDA` (a common
`li_at` prefix) should appear nowhere.

```bat
git commit -m "LinkedIn Profile API: layered acquisition, URN graph normalization, credential-free tests"
git branch -M main
git remote add origin https://github.com/<you>/linkedin-profile-api.git
git push -u origin main
```

Make the repo **public** — the challenge asks for a public repository. Once it is up, check the
Actions tab: build, secret scan and docker jobs should all go green, and the gitleaks job is
your evidence for the no-secrets requirement.

If gitleaks flags something, do not just delete the file and re-commit — it stays in history.
Easiest fix at this stage is to delete the repo on GitHub, `rmdir /s .git` locally, and start
the commit over with the file removed.

---

## 3. Deploy — 45 minutes

Render is the least friction. `render.yaml` is already in the repo.

1. render.com → **New → Web Service** → connect the GitHub repo.
2. Runtime **Docker**. It will pick up the Dockerfile.
3. Environment variables — set these in the dashboard, never in the repo:

   | Key | Value |
   |-----|-------|
   | `LINKEDIN_LI_AT` | your cookie |
   | `LINKEDIN_JSESSIONID` | your cookie |
   | `API_KEYS` | a key you invent, e.g. a UUID |
   | `VOYAGER_DECORATION_ID` | the real value from step 0, if it differs |

4. Health check path: `/actuator/health`.
5. Deploy, and watch the log for `Started LinkedInProfileApiApplication`.

First request after deploy is a cold start — the free tier sleeps. Expect a few seconds.

```bat
curl -s "https://<your-app>.onrender.com/actuator/health"
curl -s -H "X-API-Key: <your key>" "https://<your-app>.onrender.com/api/v1/profiles?url=https://www.linkedin.com/in/williamhgates"
```

**Sanity check the auth**: the same request *without* the header must return 401. If it returns
200, `API_KEYS` did not get set and your public URL is open to the world.

---

## 4. Warm the cache — 5 minutes, do not skip

A reviewer's first request should not be the one that discovers LinkedIn is throttling you.
Prime two or three profiles so the first thing they hit is served from memory.

```powershell
.\scripts\warm-cache.ps1 -BaseUrl "https://<your-app>.onrender.com" -ApiKey "<your key>"
```

With `CACHE_STALE_TTL=PT24H`, those stay servable for a day even if the session dies —
`meta.stale` will say so honestly, which is the point.

---

## 5. README and demo — 45 minutes

Fill in the two placeholders in the README:

- Replace `https://<your-deployment>` with the real URL, in the **Try it** block and the docs link.
- Put a working `curl` including the API key at the very top. The reviewer's first thirty
  seconds should be a working request, not a setup guide.

Then add, from what you actually learned in step 0:

- The real `decorationId` you observed.
- Any section that genuinely is not in the payload — move it from "not fetched" to a
  specific note. Precision here reads as someone who looked.
- Completeness numbers you actually saw for a 1st-degree vs a stranger. That single
  observation is more convincing than any amount of architecture prose.

**Record a 60-second GIF**: hit `/docs`, run one request, show the JSON, show a 422 on a private
profile. ScreenToGif is fine. Embed it at the top of the README.

---

## 6. Requirements check

Walk this before you submit. Every row is something the brief explicitly asked for.

| Required | Where | Done |
|----------|-------|------|
| Deployed publicly over HTTPS | Render URL | ☐ |
| Accepts a LinkedIn profile URL as input | `GET /api/v1/profiles?url=` | ☐ |
| Returns name, headline, location, about | `profile.name` / `.headline` / `.location` / `.about` | ☐ |
| Returns experience, education, skills | `profile.experience` / `.education` / `.skills` | ☐ |
| Returns certifications, languages | `profile.certifications` / `.languages` | ☐ |
| Returns profile images | `profile.profilePicture.sizes[]` | ☐ |
| Structured JSON | `meta` + `profile` envelope | ☐ |
| Public GitHub repo, complete source | repo is public, CI green | ☐ |
| README: setup instructions | Quick start | ☐ |
| README: API documentation | API section + live `/docs` | ☐ |
| README: your approach | Approach + Architecture | ☐ |
| README: known limitations | Known limitations | ☐ |
| No credentials or secrets in the repo | gitleaks job green | ☐ |
| Submitted at tally.so/r/KYK6gg | before 31 Aug | ☐ |

---

## 7. Submit

The form wants the hosted URL and the repo link. Include the API key if the form has a notes
field — a reviewer who hits a 401 and has no key may not write back to ask.

Suggested one-liner for a description field:

> A hosted API that resolves a LinkedIn profile URL into a stable, documented JSON contract —
> layered acquisition (authenticated Voyager API with a public-page fallback), honest
> provenance metadata on every response, and a parsing layer fully testable without
> credentials.

---

## If something breaks on the day

| Symptom | First thing to check |
|---------|---------------------|
| Everything returns `PUBLIC_HTML` | Cookie expired. Re-copy `li_at` and `JSESSIONID`, redeploy env vars. |
| Everything returns 503 | `/actuator/health` → is a breaker open, or is the session in cooldown? Both clear on their own. |
| 200s but mostly nulls | LinkedIn bumped the decoration. Set `VOYAGER_DECORATION_ID` to the current value from DevTools — no rebuild needed. |
| 401 on every request | `API_KEYS` set but you are sending the wrong header. It is `X-API-Key`, or `Authorization: Bearer`. |
| Render deploy fails on build | Check the build log for the Maven stage; the layered-jar extract step hardcodes the artifact name and version from `pom.xml`. |
| Cold start times out | Hit `/actuator/health` once to wake it, then retry. Mention it in the README. |

If the live path is dead at submission time and you cannot fix it: say so in the README, point
at the credential-free test suite as proof the logic works, and submit anyway. An honest
account of a broken upstream reads far better than a silent 503.
