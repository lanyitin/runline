# Real-browser tests: what they need

This page is the one place that lists what the real-browser tests of the Console (`npm run e2e` in
`console/`, every `e2e/*.e2e.ts` but `oidc.e2e.ts`; that one is `npm run e2e:oidc`) need, and how to prepare it from nothing (WI-57). The other
documents say what each test checks and link here for what it needs. The tests are not part of
`./gradlew check`: they need a browser, a running packaged Engine with its PostgreSQL, a keystore
made with keytool and two Fake OpenAI compatible services as processes of their own.

## What is needed, in short

| What | Needed by | Notes |
|---|---|---|
| Chrome or Chromium | every script | `CHROME_PATH` names it (default: the macOS Chrome) |
| The packaged Engine (`engine.jar` and `run-runtime/`), with the Console | every script | `./gradlew :engine:engineDistribution`; reached as `localhost` |
| A new, empty database (PostgreSQL 17), migrated | every script | Not one a contract run (`npm run test:contract`) or an earlier `npm run e2e` has used: what they leave behind (versions, triggers, resources) makes tests fail |
| `API_TOKENS` with two developers and an admin | every script | the same text in `E2E_TOKENS` |
| `RUNLINE_MAX_CONCURRENT_RUNS` of 8 | every script that makes runs | the value the scripts are verified with (several tests keep runs going side by side; the contract tests need 8 or more) |
| The sample jars, `dev/sample-pipelines/build/pipelines` | `admin`, `bindings`, `developer`, `resources`, `security`, `typed-forms`, `versions` | `./gradlew -p dev/sample-pipelines pipelineJars`; in `E2E_JARS` |
| A PKCS12 keystore the Engine opens, writable by the tests | `resources`, `resource-types`, `typed-forms`, `certificates`, `trace-export` | its entries are below; `resources` changes it with keytool and puts it back |
| A database `orders` owned by an account `reader` | `typed-forms` | the password is the secret `db-pass` |
| The Fake OpenAI compatible service over HTTP, asking for the key | `typed-forms` | `FAKE_OPENAI_KEY_FILE` (the value of `llm-key`), `FAKE_OPENAI_CHAT_DELAY_MS=15000`; in `E2E_OPENAI_URL` |
| The Fake OpenAI compatible service over TLS, requiring a client certificate | `certificates` | issued by `internal-ca`; in `E2E_TLS_OPENAI_URL` |
| A network address of the machine besides loopback | `console` | for the test of the warning on a page that is not HTTPS |
| A second Engine with login on, a third whose provider cannot be reached, and a Keycloak (WI-1202, WI-1208) | `oidc` | see *The sign-in with a provider* below; it is the one script that does not use `E2E_ENGINE_URL`, and it has its own command, `npm run e2e:oidc` |
| `keytool` of JDK 25, `openssl` | preparing the keystore; `resources` runs keytool | `E2E_KEYTOOL` names keytool (default `keytool`) |

### The entries of the keystore

| Alias | Kind of entry | What it is | Needed by |
|---|---|---|---|
| `llm-key` | secret (`keytool -importpass`) | a marker: any text that occurs nowhere else; it is `E2E_SECRET_MARKER` and the key the HTTP Fake asks for | `resources`, `resource-types`, `typed-forms`, `trace-export` |
| `db-pass` | secret | the password of the database account `reader`; it is `E2E_DB_PASSWORD` | `typed-forms` |
| `other-pass` | secret | any other text | `typed-forms` |
| `corporate-ca` | trusted certificate (`keytool -importcert`) | any certificate authority | `resources` |
| `internal-ca` | trusted certificate | the authority that issued the TLS Fake's certificate and the two client certificates | `certificates` |
| `app-client` | private key (`keytool -genkeypair`, then the certificate `internal-ca` issued) | a client certificate | `certificates` |
| `soon-client` | private key, as `app-client` | a client certificate that ends within `RUNLINE_CERTIFICATE_WARNING_DAYS` (30 by default) | `certificates` |

More entries do no harm. `E2E_PRIVATE_KEY_MARKERS` is the base64 of the private keys of
`app-client` and `soon-client` (PKCS#8, as one line each), separated by a comma; with
`E2E_KEYSTORE_PASSWORD` (the keystore's password) they are what must be in no answer, page or storage.

### The variables of the tests

| Variable | Value |
|---|---|
| `CHROME_PATH` | the browser |
| `E2E_ENGINE_URL` | `http://localhost:<PORT>` |
| `E2E_TOKENS` | the Engine's `API_TOKENS` |
| `E2E_JARS` | the absolute path of `dev/sample-pipelines/build/pipelines` |
| `E2E_SECRET_MARKER` | the value of `llm-key` |
| `E2E_KEYSTORE`, `E2E_KEYSTORE_PASSWORD_FILE` | the Engine's `RUNLINE_KEYSTORE_PATH`, `RUNLINE_KEYSTORE_PASSWORD_FILE` |
| `E2E_KEYSTORE_PASSWORD` | the keystore's password |
| `E2E_KEYTOOL` | keytool of JDK 25 (optional, `keytool` by default) |
| `E2E_DB_HOST`, `E2E_DB_PORT` | where the Engine reaches the PostgreSQL that has `orders` |
| `E2E_DB_PASSWORD` | the value of `db-pass` |
| `E2E_OPENAI_URL` | what the HTTP Fake printed |
| `E2E_TLS_OPENAI_URL` | what the TLS Fake printed |
| `E2E_PRIVATE_KEY_MARKERS` | see above |
| `E2E_SCREENSHOTS` | a directory for screenshots (optional) |
| `E2E_OIDC_ENGINE_URL` | `oidc` only: the Engine with login on, at its public address (`http://localhost:18080`) |
| `E2E_OIDC_PROVIDER_URL` | `oidc` only: the Keycloak (`http://localhost:18081`) |
| `E2E_OIDC_UNREACHABLE_ENGINE_URL` | `oidc` only: an Engine with login on whose provider cannot be reached (`http://localhost:18082`) |
| `E2E_OIDC_MAX_PENDING_LOGINS` | `oidc` only: the `RUNLINE_OIDC_MAX_PENDING_LOGINS` of the Engine at `E2E_OIDC_ENGINE_URL` (20) |

## From nothing to a run of every script

The steps below are run from the root of the repository, in one shell (bash), on a machine with
Docker, JDK 25 (with `keytool`), the Node of `.node-version`, `openssl` and a Chrome; in the
development container all of them are there but Chrome (step 1). Everything is made under `$E2E`, a
new directory. The values are for this machine only.

1. **A browser.** Use an installed Chrome, or fetch Chromium with the Playwright of the Console:
   `(cd console && npx playwright-core install chromium)` prints where it put it. Then
   `export CHROME_PATH=<the chrome executable>`.

2. **Build** the Engine with the Console and the sample jars, and install the Console's packages:
   ```
   ./gradlew :engine:engineDistribution
   ./gradlew -p dev/sample-pipelines pipelineJars
   (cd console && npm ci)
   export E2E="$(mktemp -d /tmp/runline-e2e.XXXXXX)" DIST="$PWD/engine/build/engine-dist"
   ```

3. **A new PostgreSQL 17**, with the database of the Engine and the database `orders` of `reader`:
   ```
   docker run -d --name runline-e2e-pg -e POSTGRES_DB=runline -e POSTGRES_USER=runline \
     -e POSTGRES_PASSWORD=runline -p 127.0.0.1:55433:5432 postgres:17-alpine
   until docker exec runline-e2e-pg pg_isready -h 127.0.0.1 -U runline -d runline; do sleep 1; done
   export DB_PASSWORD="reader-$(openssl rand -hex 8)"
   docker exec runline-e2e-pg psql -U runline -d runline -c "CREATE ROLE reader LOGIN PASSWORD '$DB_PASSWORD'"
   docker exec runline-e2e-pg psql -U runline -d runline -c "CREATE DATABASE orders OWNER reader"
   ```
   The image may be pulled from a mirror (for example `mirror.gcr.io/library/postgres:17-alpine`).
   `pg_isready` asks over TCP on purpose: while the image prepares the database it runs a server of
   its own on the socket only, and restarts it afterwards.

4. **The keystore** (`$E2E/keystore/runline.p12`, outside the Engine's directories) and the TLS
   Fake's keystore, both made with keytool; `internal-ca` signs the server's and the clients'
   certificates:
   ```
   mkdir -p "$E2E/keystore" && cd "$E2E/keystore"
   openssl rand -hex 16 > password && chmod 600 password
   export MARKER="marker-$(openssl rand -hex 12)"
   K="-storetype PKCS12 -storepass:file password"
   keytool -genkeypair -alias internal-ca -keyalg EC -groupname secp256r1 -dname CN=internal-ca \
     -ext bc:c -validity 365 -keystore ca.p12 $K
   keytool -exportcert -rfc -alias internal-ca -keystore ca.p12 $K > internal-ca.pem
   keytool -genkeypair -alias corporate-ca -keyalg EC -groupname secp256r1 -dname CN=corporate-ca \
     -ext bc:c -validity 365 -keystore other-ca.p12 $K
   keytool -exportcert -rfc -alias corporate-ca -keystore other-ca.p12 $K > corporate-ca.pem
   for alias in internal-ca corporate-ca; do
     keytool -importcert -noprompt -alias $alias -file $alias.pem -keystore runline.p12 $K
   done
   printf '%s\n' "$MARKER" | keytool -importpass -alias llm-key -keystore runline.p12 $K
   printf '%s\n' "$DB_PASSWORD" | keytool -importpass -alias db-pass -keystore runline.p12 $K
   printf '%s\n' "other-$(openssl rand -hex 8)" | keytool -importpass -alias other-pass -keystore runline.p12 $K
   issue() { # alias, keystore, days, extension
     keytool -genkeypair -alias $1 -keyalg EC -groupname secp256r1 -dname CN=$1 -keystore $2 $K
     keytool -certreq -alias $1 -keystore $2 $K \
       | keytool -gencert -alias internal-ca -keystore ca.p12 $K -validity $3 -rfc ${4:+-ext $4} > $1.pem
     keytool -importcert -noprompt -alias internal-ca -file internal-ca.pem -keystore $2 $K 2>/dev/null || true
     keytool -importcert -noprompt -alias $1 -file $1.pem -keystore $2 $K
   }
   issue app-client runline.p12 365
   issue soon-client runline.p12 10
   issue localhost server.p12 365 san=dns:localhost
   export KEY_MARKERS="$(for alias in app-client soon-client; do
     openssl pkcs12 -in runline.p12 -passin file:password -nocerts -nodes 2>/dev/null \
       | awk -v a=$alias '/friendlyName/ {on = ($2 == a)} on && /BEGIN PRIVATE KEY/ {k=1; next}
                          on && /END PRIVATE KEY/ {k=0; on=0} k' | tr -d '\n'; echo; done | paste -sd, -)"
   printf '%s\n' "$MARKER" > "$E2E/openai-key"
   cd -
   ```

5. **The two Fake services** (each in a shell of its own, or in the background as here); each prints
   `FAKE_OPENAI_URL=...` and writes it to the file named by `FAKE_OPENAI_URL_FILE`:
   ```
   FAKE_OPENAI_KEY_FILE="$E2E/openai-key" FAKE_OPENAI_CHAT_DELAY_MS=15000 \
     FAKE_OPENAI_URL_FILE="$E2E/openai-url" ./gradlew :accessors:fakeOpenAiServer > "$E2E/fake-http.log" 2>&1 &
   FAKE_OPENAI_TLS_KEYSTORE="$E2E/keystore/server.p12" FAKE_OPENAI_TLS_PASSWORD_FILE="$E2E/keystore/password" \
     FAKE_OPENAI_TLS_CLIENT_CA="$E2E/keystore/internal-ca.pem" FAKE_OPENAI_URL_FILE="$E2E/openai-tls-url" \
     ./gradlew :accessors:fakeOpenAiServer > "$E2E/fake-tls.log" 2>&1 &
   until [ -s "$E2E/openai-url" ] && [ -s "$E2E/openai-tls-url" ]; do sleep 1; done
   ```

6. **The Engine**: the migration, then the Engine, with the settings of *Engine runs: packaging and
   configuration* in the root README. `java` here must be JDK 25, the version the Engine is built for:
   a `java` of JDK 21 on the `PATH` fails at once with `UnsupportedClassVersionError`; put JDK 25 first
   on `PATH`, or set `JAVA_HOME`.
   ```
   export PORT=8080 POSTGRES_URL=jdbc:postgresql://localhost:55433/runline POSTGRES_USER=runline POSTGRES_PASSWORD=runline
   export API_TOKENS="ada:developer:ada-$(openssl rand -hex 8),bob:developer:bob-$(openssl rand -hex 8),root:admin:root-$(openssl rand -hex 8)"
   export RUNLINE_RUNTIME_DIR="$DIST/run-runtime" RUNLINE_MAX_CONCURRENT_RUNS=8
   export RUNLINE_SHARED_ROOT="$E2E/shared" RUNLINE_RUN_ROOT="$E2E/runs" RUNLINE_RESOURCE_ROOT="$E2E/resources"
   export RUNLINE_WORKSPACE_MAX_BYTES=104857600 RUNLINE_FAILED_RUN_RETENTION_SECONDS=3600
   export RUNLINE_KEYSTORE_PATH="$E2E/keystore/runline.p12" RUNLINE_KEYSTORE_PASSWORD_FILE="$E2E/keystore/password"
   export OTEL_TRACES_EXPORTER=none OTEL_LOGS_EXPORTER=none OTEL_METRICS_EXPORTER=none
   mkdir -p "$RUNLINE_SHARED_ROOT" "$RUNLINE_RUN_ROOT" "$RUNLINE_RESOURCE_ROOT"
   java -cp "$DIST/engine.jar" dev.lawlan.runline.engine.db.MigrateKt
   java -jar "$DIST/engine.jar" > "$E2E/engine.log" 2>&1 &
   until curl -fs http://localhost:$PORT/api/v1/health/ready > /dev/null; do sleep 1; done
   ```

7. **The tests**:
   ```
   export E2E_ENGINE_URL=http://localhost:$PORT E2E_TOKENS="$API_TOKENS" \
     E2E_JARS="$PWD/dev/sample-pipelines/build/pipelines" E2E_SECRET_MARKER="$MARKER" \
     E2E_KEYSTORE="$RUNLINE_KEYSTORE_PATH" E2E_KEYSTORE_PASSWORD_FILE="$RUNLINE_KEYSTORE_PASSWORD_FILE" \
     E2E_KEYSTORE_PASSWORD="$(cat "$E2E/keystore/password")" E2E_DB_HOST=localhost E2E_DB_PORT=55433 \
     E2E_DB_PASSWORD="$DB_PASSWORD" E2E_OPENAI_URL="$(cat "$E2E/openai-url")" \
     E2E_TLS_OPENAI_URL="$(cat "$E2E/openai-tls-url")" E2E_PRIVATE_KEY_MARKERS="$KEY_MARKERS"
   (cd console && npm run e2e)
   ```
   One script alone: `(cd console && npx vitest run --config vitest.e2e.config.ts e2e/<name>.e2e.ts)`.
   `npm run e2e` does not run `oidc.e2e.ts`: that script needs a Keycloak and Engines with login on,
   which the day to day run does not, and has its own command, `npm run e2e:oidc` (*The sign-in with a
   provider* below). Neither command looks at the environment to decide what to run: a script that
   lacks what it needs fails.

8. **Afterwards**: stop the Engine and the two Fakes (`kill %1 %2 %3`, or by their process ids),
   `docker rm -f runline-e2e-pg` and `rm -rf "$E2E"`. To run the tests again, start again at step 3
   (a new database), or at least with a new database and a new Engine.

`dev/verify-console-security.sh` prepares its own Engine (with `dev/dev.sh`) and runs only
`security.e2e.ts`, which needs only the Engine, the tokens and the sample jars of the list above.
It needs no environment variable for the resource root: `dev/dev.sh` uses `dev/.runline/resources`
(git-ignored, removed by `dev/dev.sh clean`) unless `RUNLINE_RESOURCE_ROOT` is already exported, in
which case your value is used as it is.

## The sign-in with a provider (`e2e/oidc.e2e.ts`, WI-1202)

This script signs in at a real OpenID Connect provider, so it needs two things the others do not:
a Keycloak with the realm of the Engine's own tests, and an Engine with login on whose public
address is the one the realm registers, and (WI-1208) a second Engine with login on whose provider
cannot be reached. It is run by `npm run e2e:oidc`, not by `npm run e2e`: before a release and by
WI-1204, not in the day to day run. It also needs the database, the packaged Engine, the sample
jars (`E2E_JARS`: it uploads one) and the browser of the steps above. Its variables are the four
`E2E_OIDC_*` of the table, `E2E_TOKENS` (the Engine's `API_TOKENS`: the token still signs in) and
`E2E_JARS`. Run from the root of the repository, after steps 1 to 3 above (the Engine of step 6 is
another one, with login off, that the other scripts use; this one is a second Engine, on its own
database, so that nothing a script left behind matters):

1. **Keycloak** (the same version as the Engine's tests), importing the realm:
   ```
   docker run -d --name runline-e2e-kc -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
     -v "$PWD/engine/src/test/resources/oidc/runline-realm.json:/opt/keycloak/data/import/runline-realm.json:ro" \
     -p 127.0.0.1:18081:8080 quay.io/keycloak/keycloak:26.4 start-dev --import-realm
   until curl -fs http://localhost:18081/realms/runline/.well-known/openid-configuration > /dev/null; do sleep 2; done
   ```
   The realm has the confidential client `runline` (secret `client-secret-for-tests`, PKCE) whose
   redirect address is `http://localhost:18080/api/v1/auth/oidc/callback`, and the groups
   `runline-admin` and `runline-developer`; the script makes its people through the administration
   API (user `admin`, password `admin`).
2. **A database for that Engine** (`docker exec runline-e2e-pg psql -U runline -d runline -c "CREATE DATABASE runline_oidc"`),
   then the migration and the Engine as in step 6 with these changed and added (the port and the
   address must be those of the realm, the issuer the one the browser reaches too):
   ```
   export PORT=18080 POSTGRES_URL=jdbc:postgresql://localhost:55433/runline_oidc
   export RUNLINE_SHARED_ROOT="$E2E/shared-oidc" RUNLINE_RUN_ROOT="$E2E/runs-oidc" RUNLINE_RESOURCE_ROOT="$E2E/resources-oidc"
   export RUNLINE_OIDC_ISSUER=http://localhost:18081/realms/runline RUNLINE_OIDC_CLIENT_ID=runline \
     RUNLINE_OIDC_CLIENT_SECRET=client-secret-for-tests RUNLINE_PUBLIC_BASE_URL=http://localhost:18080 \
     RUNLINE_SESSION_ENCRYPTION_KEY="$(head -c 32 /dev/urandom | base64)"
   ```
   and `RUNLINE_OIDC_MAX_PENDING_LOGINS=20` (the test fills the room for sign-ins under way, which
   stays full for ten minutes: start this Engine new for each run). The keystore settings may stay
   unset for this script.
3. **An Engine whose provider cannot be reached**: the same settings and database, with
   `PORT=18082 RUNLINE_PUBLIC_BASE_URL=http://localhost:18082 RUNLINE_OIDC_ISSUER=http://localhost:1/realms/runline`
   (nothing listens there; the Engine still starts and is ready).
4. **The script:**
   ```
   (cd console && E2E_OIDC_ENGINE_URL=http://localhost:18080 E2E_OIDC_PROVIDER_URL=http://localhost:18081 \
     E2E_OIDC_UNREACHABLE_ENGINE_URL=http://localhost:18082 E2E_OIDC_MAX_PENDING_LOGINS=20 \
     E2E_TOKENS="$API_TOKENS" npm run e2e:oidc)
   ```

What it checks, with a real browser (beside the rest below, when the start of a sign-in fails the
browser is back at the Console's sign-in with words and nothing in the address: once with the provider
unreachable, once with no room for another sign-in): an admin signs in at the provider and lands on the page asked
for (`/allowlist`) with the pages of an admin, the cookie is `HttpOnly` and `SameSite=Lax`, no
credential is in `sessionStorage`, `localStorage`, `document.cookie` or the page, and a jar goes up
as that person (cookie and CSRF token); a developer has no admin page and is refused the address; a
person in neither group is refused in words (English and Chinese) with nothing in the address and no
cookie; signing out puts the tab at the sign-in and a second tab by its next request, on its own
page, and signing in leads back to it; a token still signs in beside the provider; and the endpoints
pass `contract/auth-contract.ts`, the same tests the Fake Engine passes in `npm test`. The user
administration (WI-1206) is in the same script: the endpoints pass `contract/users-contract.ts` (the
Fake Engine passes it too); an admin opens the Users page, disables a developer and the developer's
tab is at the sign-in on its next action, signing in again is refused in words until the admin
enables the person; an admin ends a session of a person from the page; a developer has no Users page
in the navigation and the address is turned away. An Engine with
login on answers the sign-in page's one extra question with a 401 (`GET /api/v1/auth/session` while
nobody is signed in), which a browser lists in its console: the other scripts, which assert that the
console is empty, are run against the Engine with login off.

## The phone sizes (`e2e/responsive*.e2e.ts`, WI-61 to WI-64)

The narrow layout of the Console (ADR-048: [docs/stable/console-responsive](../../docs/stable/console-responsive/README.md)) is verified in the same real browser, against the same Engine, with the same variables and steps as the scripts above; there is no command of its own and nothing more to prepare. The browser window is made 360px, 390px or 768px wide (`PHONE_360`, `PHONE_390`, `NARROWEST_WIDE` in `e2e/browser.ts`; 767px, the last narrow width, is also used by the scripts of the pages). A script of the phone sizes is part of `npm run e2e`; one alone:

```
(cd console && npx vitest run --config vitest.e2e.config.ts e2e/responsive)   # all four
(cd console && npx vitest run --config vitest.e2e.config.ts e2e/responsive-routes.e2e.ts)
```

| Script | What it checks |
|---|---|
| `responsive.e2e.ts` | the foundation: the breakpoint, the drawer of the navigation, the top bar, the size of what is touched |
| `responsive-information.e2e.ts` | runs, log, pipelines, views and the overview: cards, tables that scroll in their own container |
| `responsive-admin.e2e.ts` | the pages of administration, the forms and the dialogs |
| `responsive-routes.e2e.ts` | every route of `ROUTES`, as a developer and as an admin: with data, nothing found, refused; at each size the page does not scroll sideways, the navigation opens and closes and each of its links can be pressed, and the main action of the page can be pressed |

`responsive-routes.e2e.ts` fails when a route is added to `src/app/routes.ts` and not named in its list of cases, so a new page cannot escape the check. The page of users exists only on an Engine with login with a provider and is verified at 360px, 390px and 767px by `oidc.e2e.ts` (`npm run e2e:oidc`). What this does not verify: a real phone, the Safari of iOS (its zoom on a focused field, the bars of its window), the touch of a finger rather than the click of a browser, and a landscape phone.

## The dark mode (`e2e/dark-mode.e2e.ts`, WI-71)

The Console follows the system preference for dark ([ADR-70](../../docs/stable/console-dark-mode/adr/ADR-70-console-dark-mode.md)). The script emulates the preference in the same real browser, against the same Engine, and needs only the Engine, `E2E_TOKENS` and `E2E_JARS`. For every route of `ROUTES` (as a developer or an admin) it checks that the body has the dark tokens, that no pale block is left, and that every text reaches 4.5:1 (3:1 for large text) against the backgrounds laid under it. It also checks that the log panel is dark with its border, that the light preference is the Console as it was, and that a change of the preference takes effect on the open page without a reload. Like `responsive-routes.e2e.ts` it fails when a route is added and not named. The page of users exists only on an Engine with login with a provider and is not walked here; it uses the same tokens.
