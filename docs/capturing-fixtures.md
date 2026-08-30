# Capturing real Voyager fixtures

The committed fixture is **synthetic**. It reproduces the response *shape* faithfully —
`data.*elements` pointing into `included[]`, roles nested inside a position group, a
company that points back at the member, images as `rootUrl` + `artifacts[]` — but the field
paths the mappers read are informed guesses until they are checked against a real payload.

This is the highest-value hour of work left on the project. Everything downstream of the
mapper is already tested; the mapper itself is only as correct as the fixtures behind it.

---

## 1. Capture

> **Do not copy the response out of DevTools.** That was the original advice here and it is
> wrong now. The LinkedIn web app has moved to `/voyager/api/graphql`, while `VoyagerClient`
> still calls the REST-li endpoint `/voyager/api/identity/dash/profiles` with a
> `decorationId`. Copying from the Network tab therefore captures a request this service
> never makes, and pins the mappers to the wrong shape.
>
> Two specific traps, both hit in practice:
>
> - The profile page fires **several** calls against `identityDashProfilesByMemberIdentity`.
>   One of them is a cache-validation call whose projection is only `entityUrn` and
>   `versionTag` — a couple of KB that look like a real capture in a file listing and
>   contain no profile data at all.
> - GraphQL responses nest the collection at `data.data.<queryName>."*elements"` rather than
>   `data."*elements"`. `UrnGraph` understands both, but the entity shapes inside differ.

Replay the request the service itself makes:

```powershell
.\scripts\capture-voyager.ps1 -Slug "https://www.linkedin.com/in/<someone>"
```

It reads the cookies from `.env`, builds the same URL and headers as `VoyagerClient`,
refuses to follow redirects, and writes the raw response to `fixtures/raw/<slug>.json`. Add
`-DryRun` to see the identifier, URL and output path without spending a request, and
`-CheckSession` to test the cookie against `/voyager/api/me` on its own.

It also reads the failures for you: a 302 whose `Location` is `/authwall` is a rejected
cookie, a `Set-Cookie` that expires `li_at` in 1970 is LinkedIn deleting your session token,
and a 302 back to the *same* URL is the datacenter handshake, which it completes with exactly
one retry.

Then confirm the capture is worth keeping before spending a redaction cycle on it:

```powershell
.\scripts\check-capture.ps1 src\test\resources\fixtures\raw\*.json
```

Capture **three** profiles, because they will not look the same:

| Capture | Why it matters |
|---------|----------------|
| Your own profile | The richest possible payload — every section populated |
| A 1st-degree connection | What a "good" third-party result looks like |
| A stranger (2nd/3rd degree) | Materially thinner. This is what most API calls will actually hit |

That third one is the point of `meta.completeness`, and having it as a fixture is what stops
you from over-promising in the README.

**On the `decorationId`.** The default is `…FullProfileWithEntities-93`, and because the
browser no longer calls this endpoint there is no longer a request in the Network tab to read
the current value from. If captures come back 400 or 404 while the session is demonstrably
alive (`-CheckSession` returns 200), a retired decoration is the first suspect — try a
neighbouring version via `VOYAGER_DECORATION_ID`, which needs no rebuild.

**Do not commit the scratch files.** `*.har` and `src/test/resources/fixtures/raw/` are both
gitignored; put raw captures in `fixtures/raw/` and they cannot be committed by accident.

---

## 2. Redact

```bash
java tools/FixtureRedactor.java \
     src/test/resources/fixtures/raw/my-profile.json \
     src/test/resources/fixtures/voyager-real-full.json
```

Single-file, JDK-only, no build step. It:

- remaps every member id (`ACoA…`) to a stable placeholder — **the same id maps to the same
  placeholder everywhere**, so the URN graph still resolves after redaction;
- replaces names, headlines, summaries, descriptions, locations, organisation names,
  credential numbers and media URLs;
- keeps every key, the pointer structure, array lengths, date objects, image dimensions and
  `$type` discriminators, so the fixture still exercises the same code paths;
- preserves employment- and workplace-type vocabulary (`Full-time`, `Hybrid`, …) so the enum
  normalization stays testable;
- finishes with a residual scan for cookies, CSRF tokens, email addresses, `licdn.com` URLs
  and unmapped member ids, and **exits non-zero** if it finds any.

A clean scan is a first pass, not a guarantee. Read the output file before committing it.

---

## 3. Pin the mappers

**Start with the report, not the fixture.** Name the redacted file `voyager-real*.json` and:

```bash
mvn test -Dtest=RealFixtureMappingReportTest
```

`RealFixtureMappingReportTest` maps every `voyager-real*.json` in the fixtures directory and
prints, per field, whether it resolved — and for each one that did not, how far the path got
and which keys were actually available where it stopped:

```
  - location.raw
      geoLocation.geo.defaultLocalizedName  ->  no 'geo' under 'geoLocation'; available: geoUrn, preferredGeoPlace
      geoLocationName                       ->  not on the profile entity (see PAYLOAD KEYS)
```

That names the fix — add `geoLocation.preferredGeoPlace.defaultLocalizedName` to the candidate
list — without opening the payload. A `PAYLOAD KEYS` section then dumps the profile entity's
real keys and the keys of the first element of each collection, which is where per-item paths
(`title`, `companyName`, `dateRange`) get verified.

The report **skips when no `voyager-real*.json` exists**, so a clean clone with no credentials
still goes green. That is the property the README promises a reviewer, and it is worth not
breaking.

Once the paths are right, pin the result with real assertions:

```java
@Test
void mapsARealProfile() {
    JsonNode resolved = UrnGraph.of(Fixtures.load("voyager-real-full.json"))
            .rootProfile().orElseThrow();

    Profile profile = new VoyagerProfileMapper()
            .map(resolved, "member-one", "https://www.linkedin.com/in/member-one");

    assertThat(profile.experience()).hasSize(/* the real number of roles */);
    assertThat(profile.experience().get(0).startDate()).isNotNull();
    assertThat(profile.skills()).isNotEmpty();
    assertThat(new CompletenessScorer().score(profile)).isGreaterThan(0.8);
}
```

**Where the guesses are.** Every read in `VoyagerProfileMapper` goes through
`Json.text(node, "path.a", "path.b", …)` — a list of candidate paths tried in order. When a
field comes back null against a real fixture, the fix is almost always to add the real path
to that list rather than to restructure anything. The paths most worth verifying first:

| Field | Candidates currently tried |
|-------|---------------------------|
| Location | `geoLocation.geo.defaultLocalizedName`, `geoLocationName`, `locationName` |
| Industry | `industry.name`, `industryV2.name`, `industryName` |
| Badges | `memberBadges.openToWork`, `openToWork` |
| Follower count | `followingState.followerCount`, `followersCount` |
| Connections | `connections.paging.total`, `connectionsCount` |
| Skill endorsements | `endorsementCount`, `endorsedCount`, `insights.endorsementCount` |
| Employment type | `employmentType.name`, `employmentType`, `employmentTypeUrn` |
| Workplace type | `workplaceType.name`, `workplaceType`, `locationType` |

Connections and endorsements are the two most likely to be nowhere near these paths — both
tend to live behind their own decorations. If they are genuinely absent from the payload, say
so in the README's limitations list rather than leaving a silently-null field.

---

## 4. Sanity-check the live path

Once the fixtures pass, run the service against the real thing **once or twice, not in a
loop**:

```bash
set -a && . ./.env && set +a
mvn spring-boot:run

curl -s "http://localhost:8080/api/v1/profiles?url=https://www.linkedin.com/in/<someone>" | jq .meta
```

Read `meta.source` and `meta.completeness` first. `VOYAGER` with a high score means the whole
chain works. `PUBLIC_HTML` means the cookie is not being accepted — check
`/actuator/health` for the session state before touching anything else, and remember that a
quarantined session stays down for the configured cooldown.
