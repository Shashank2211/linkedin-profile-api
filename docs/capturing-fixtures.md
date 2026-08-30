# Capturing real Voyager fixtures

The committed fixture is **synthetic**. It reproduces the response *shape* faithfully —
`data.*elements` pointing into `included[]`, roles nested inside a position group, a
company that points back at the member, images as `rootUrl` + `artifacts[]` — but the field
paths the mappers read are informed guesses until they are checked against a real payload.

This is the highest-value hour of work left on the project. Everything downstream of the
mapper is already tested; the mapper itself is only as correct as the fixtures behind it.

---

## 1. Capture

1. Log in to LinkedIn in a browser you don't mind treating as disposable.
2. Open DevTools → **Network**. Filter the request list on `voyager`.
3. Load a profile and watch for a request to
   `identity/dash/profiles?q=memberIdentity&…`. That is the one.
4. Right-click it → **Copy → Copy response**, and paste into a scratch file.

Capture **three** profiles, because they will not look the same:

| Capture | Why it matters |
|---------|----------------|
| Your own profile | The richest possible payload — every section populated |
| A 1st-degree connection | What a "good" third-party result looks like |
| A stranger (2nd/3rd degree) | Materially thinner. This is what most API calls will actually hit |

That third one is the point of `meta.completeness`, and having it as a fixture is what stops
you from over-promising in the README.

While you're in the Network tab, note the exact `decorationId` in the request URL. If it is
not `…FullProfileWithEntities-93`, put the real value in `VOYAGER_DECORATION_ID` — no
rebuild needed.

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
