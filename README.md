# keycloak-research-id-provider

Mints and supplies each person's **research id**, for example `rc-4821-0937`, inside Keycloak, so every service that signs people in through Keycloak (DASHR, AIS, …) gets it in the token from the very first sign-in.

Design background: `base-dashr/comments/Identity.md`, section 5 (decision D3, gap G1).

## The research id

- `rc-`, seven random digits and one check character, grouped in fours: `rc-4821-0937`. 10 million possible ids.
- The check character is ISO 7064 MOD 11-2, as ORCID uses, so it's a digit or `x`. A mistyped id is caught instead of finding someone else.
- Random, not a running count: it reveals nothing about how many people have signed up, and neighbours can't be guessed. Generated with `SecureRandom`.
- Stored in lower case. No personal information.
- Never changes and is never reused, even after the account is deleted or merged.

Reading typed ids (`ResearchId.parse`) ignores case, spaces, underscores and missing hyphens, but requires the `rc` prefix and a correct check character: `RC 48210937`, `rc_4821_0937` and `rc48210937` all read as `rc-4821-0937`.

## How it stays unique with several replicas

Keycloak has no unique constraint on user attributes, and a Java lock (`synchronized`) only works inside one replica. So uniqueness is enforced by Postgres, which all replicas share:

1. The provider adds a table, `RESEARCH_ID_RESERVATION`, to Keycloak's database through Keycloak's supported JPA entity SPI. Keycloak creates and upgrades it with Liquibase (`src/main/resources/META-INF/research-id-changelog.xml`).
   - The primary key `RESEARCH_ID` means an id can be reserved only once.
   - The unique constraint on `(REALM_ID, USER_ID)` means a person can hold only one.
   - Rows are never updated or deleted, so ids are never reused.
2. At sign-in, the minter picks a random id and inserts it in its **own short transaction**, committed straight away:
   - Postgres checks both constraints at insert time, and makes a competing insert of the same key wait for the first to commit, so two replicas can never both succeed.
   - If the insert is rejected, the minter looks again: either another replica has just given this person an id (it uses that one), or the id was taken (it tries a new one, up to 20 times).
3. Then it sets the user attribute `research_id`, which a token mapper puts in the token.

The id is reserved before the sign-in finishes. If the sign-in then fails, the id is simply never used.

## What the sign-in step does

The authenticator **Research id** (`research-id-ensure`) runs at each sign-in:

| The user's `research_id` attribute | What happens |
|---|---|
| Missing | Uses the id reserved for them if there is one (the attribute was removed), otherwise reserves a new one; sets the attribute |
| Present, reserved for them | Nothing more (one lookup by primary key or unique index) |
| Present, valid, reserved for nobody (set by an admin, or imported) | Reserves it for them and keeps it |
| Present, typed in another form (`RC-4821-0937`) | As above, then stores the standard form |
| Several copies of the same id (see below) | Collapses them to one |
| Malformed, several different ids, or reserved for someone else | **Sign-in refused**, logged as an error. Fix the attribute in the admin console |

**Simultaneous first sign-ins of one person** (for example a double-clicked button, or two tabs) all get the same id, because the reservation decides it. But Keycloak's own user-attribute table has no unique constraint, so each of those requests may add its own copy of the attribute. The token is still right (the mapper logs a "Multiple values found" warning and uses the first, and all copies are equal), and the next sign-in collapses the copies to one.

## Build and test

Needs Java 21 or later and Maven. The integration tests also need Docker.

```sh
mvn test     # unit tests
mvn verify   # plus TwoReplicasIT: two Keycloak 26.8.1 replicas on one Postgres, simultaneous sign-ins
```

The jar is `target/keycloak-research-id-provider-<version>.jar`. It has no dependencies of its own: everything it uses is provided by Keycloak.

**Behind a TLS-inspecting proxy on macOS** (Maven fails with `PKIX path building failed`), have Java trust the macOS keychain for that run:

```sh
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=KeychainStore" mvn verify
```

`TwoReplicasIT` proves on real Keycloak that:

- 40 new people signing in at once across both replicas get 40 valid, distinct ids, with one reservation each;
- one person signing in 20 times at once across both replicas gets one id, and the next sign-in leaves exactly one attribute value;
- the id never changes on later sign-ins;
- a removed attribute is restored to the same id;
- a free id set by an admin is kept and reserved;
- several copies of the same id are collapsed to one;
- several different ids on one account are refused;
- an id belonging to someone else is refused.

## Deploy

The provider must match the Keycloak version: `keycloak.version` in `pom.xml` is the version it's compiled and tested against (26.8.1). Re-run `mvn verify` against the new version before each Keycloak upgrade.

Build a Keycloak image with the jar in `/opt/keycloak/providers/`. The `Dockerfile` here does that and pre-builds Keycloak's configuration for Postgres:

```sh
mvn verify
docker build -t <registry>/keycloak-research:26.8.1-<provider-version> .
```

Then point Keycloak at that image: `keycloak_image` in `dashr-platform-gitops/clusters/<env>/cluster-vars.yaml`. With the pre-built image, the container can run `start --optimized`.

**Local cluster:** `scripts/build-local.sh <tag>` builds the jar and the image `registry.local/dashr/keycloak-research:<tag>` and puts it on the Docker Desktop node (`kind` is the other target). Use a new tag for each build, for example `26.8.1-0.1.0-<commit>`, then set it as `keycloak_image` in `clusters/local/cluster-vars.yaml`.

On first start, Keycloak runs the changelog and creates `RESEARCH_ID_RESERVATION`. Check with `\d research_id_reservation` in the `keycloak` database.

## Configure the realm

`scripts/configure-realm.sh` does steps 1, 2, 3 and 5 below, and is safe to re-run (for example after adding an identity provider). It runs `kcadm.sh` inside the Keycloak pod and asks for a master-realm admin login. `REALM` and `CLIENTS` choose the realm and the clients that get the scope (default `research-services` and `research-portal`).

By hand, all in the `research-services` realm, in this order:

1. **Declare the attribute.** *Realm settings → User profile → Create attribute*: name `research_id`, display name "Research id". Permissions: **view** by admin and user, **edit** by admin only. Not required (Keycloak sets it, not the person).
2. **Create the flow.** *Authentication → Create flow*: name `research id`, type *basic flow*. Add the step **Research id**, set to *Required*.
3. **Run it after every brokered sign-in.** For each identity provider (`aaf`, `google`, …): *Identity providers → (provider) → Post login flow* = `research id`. It runs after the first broker login has created the user, and on every later sign-in through that provider.
4. **Other ways in** (only if people can sign in without a provider, for example with a password): add **Research id** as a *Required* step at the end of that flow's forms sub-flow.
5. **Put it in tokens, for every service.** *Client scopes → Create client scope*: `research-id`, type *Default*, *Include in token scope* on. Add a mapper *By configuration → User Attribute*: user attribute `research_id`, token claim name `research_id`, claim JSON type String, added to the ID token, access token, userinfo and introspection. Then add the scope to each client (`research-portal`, AIS, …) as a **default** scope, so every token carries it.

## Operating notes

- **A second database connection, briefly, at each sign-in.** The reservation lookup (and, the first time, the insert) runs in its own short transaction, on a second connection from Keycloak's pool while the sign-in holds one. Keep `KC_DB_POOL_MAX_SIZE` comfortably above peak concurrent sign-ins.
- **Running out of ids.** Clashes stay rare until most of the 10 million ids are used. If minting ever logs `No research id could be reserved after 20 attempts`, the id space needs widening (a new format version).
- **Audit.** Each new id is logged (`Research id … set for user … in realm …`) and added to the sign-in event as the detail `research_id`. Turn on *Save events* in the realm to keep them.
- **Finding a person from a research id.** *Users* → search by attribute `research_id`, or `select user_id from research_id_reservation where research_id = 'rc-4821-0937'`. The table also keeps ids of deleted accounts.
- **Never change an id by hand.** The sign-in step refuses an id that differs from the person's reservation. Merges and corrections need their own procedure (design notes, section 9).
