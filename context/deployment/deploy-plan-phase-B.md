# Plan: Wdrożenie GarageOps Faza B — Postgres + GitHub Actions + Railway agent tooling

## Context

Faza A landed czysto w commit `1382676 "Add Railway deploy gates (Phase A): actuator, Dockerfile, railway.json"` — Spring Boot 4.0.6 / Java 21 deploy na Railway w regionie Amsterdam działa, healthcheck `/actuator/health` zielony, **bez bazy danych**. Faza B dokłada warstwę persystencji i automatyzację deploya — wciąż przed warstwą domeny.

**Cel Fazy B**: prowizja managed Postgres co-located z aplikacją, wpięcie Spring Boot przez `PG*` env vary (NIE `DATABASE_URL` — risk z `infrastructure.md:146`), uruchomienie migracji Flyway przeciw świeżej DB, oraz CI/CD przez GitHub Actions z `railway up` na push do `main`. Plus instalacja Railway agent tooling (skills + lokalny stdio MCP) sugerowana przez `railway list`. Schemat domeny **wciąż odłożony** — V1 to smoke-test migracja udowadniająca że całość się zazębia, bez wchodzenia w encje z PRD.

**Decyzje potwierdzone z użytkownikiem (sesja 2026-05-25)**:
- GitHub: workflow `.github/workflows/deploy.yml` na push do `main` + `workflow_dispatch`
- Migracje: **Flyway** (konwencja Spring Boot, plain SQL, brak ceremonii — Liquibase odrzucony)
- Zakres DB: **wiring + V1 smoke-test** (tabela `deploy_smoke_test` z jednym INSERT — bez encji domeny)
- MCP: **lokalny stdio** (`railway mcp install --agent claude-code` w trybie domyślnym, bez OAuth)

**Co Faza B udowadnia end-to-end**:
1. PostgreSQL prowizjonowany przez Railway łączy się z aplikacją w runtime.
2. Flyway uruchamia migracje na starcie aplikacji, bez crash-loopa.
3. HikariCP pool jest poprawnie zsizowany pod 512MB tier (nie OOM-uje).
4. GitHub Actions przeprowadza `mvnw verify` + `railway up` automatycznie na merge do `main`.
5. Railway agent tooling odpowiada na zapytania o stan projektu z poziomu Claude Code.

---

## Human gates (must-do przez użytkownika, agent NIE ma dostępu)

Z risk registera `infrastructure.md` i z natury Fazy B — następujące akcje **muszą zostać wykonane ręcznie przez właściciela konta**:

1. **`railway add --database postgres`** — sama komenda CLI'owa po wyborze nazwy serwisu. Agent może to wykonać, ALE proces wymaga interakcji w dashboardzie zaraz potem (gate 2). Bezpieczniej: właściciel konta uruchamia to po przejrzeniu planu.
2. **Dashboard Railway → Postgres service → Settings → Backups → Enable daily backups** — **CRITICAL day-one gate**. Brak CLI flag na tę akcję (stan na 2026-05-24, `infrastructure.md:104`). Likelihood "High until done", Impact "Catastrophic" (PRD guardrail "No silent data loss"). **Bez tego Faza B nie jest zaliczona.**
3. **Dashboard Railway → świadome NIE-akcje** (potwierdzenie, że pozostają wyłączone z Fazy A):
   - **NIE włączać** App Sleeping (1s NFR z PRD).
   - **NIE włączać** PR Preview Environments (po dodaniu Postgresa to ryzyko klobują preview-em production DB — `infrastructure.md:92,108`).
4. **GitHub repo → Settings → Secrets and variables → Actions → New repository secret**:
   - Nazwa: `RAILWAY_TOKEN`
   - Wartość: project-scoped token z dashboard Railway → Project → Settings → Tokens → Create New Token (NIE account-scoped `RAILWAY_API_TOKEN` — risk z `infrastructure.md:76`). Project-scoped automatycznie wiąże token z konkretnym projektem.
5. **`railway login`** (jeśli sesja CLI wygasła) i `railway link` do projektu przed `railway add --database postgres`.
6. **Przegląd i akceptacja tego planu** przed jakąkolwiek mutacją na Railway.
7. **(Po pierwszym CI-deploy zielonym)**: weryfikacja w dashboardzie, że memory usage `garageops` < 450MB w spoczynku.

Wszystkie inne kroki (edycja plików w repo, lokalny `mvnw verify`, lokalne testowanie z Docker Postgres, `railway up` z CLI, instalacja skills/MCP) wykonuje agent.

---

## Pliki do utworzenia / zmiany

Wszystkie ścieżki względem `C:\Workspaces\WorkspacesTraining\garage\garage\`.

### 1. `pom.xml` — cztery dependency po `spring-boot-starter-actuator`

Dopisać blok bezpośrednio po dependency actuatora (po linii 40), zachowując tabowe wcięcie zgodnie z AGENTS.md hard rule:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

**Dlaczego `-jdbc` jawnie**: `flyway-core` ciągnie HikariCP transytywnie, ALE nie ciąga Spring Boot `DataSourceAutoConfiguration`. Bez `-jdbc` `spring.datasource.*` zostaje cicho zignorowany → "no DataSource bean". Faza B by się wywaliła z trudno-debugowalnym błędem.

**Dlaczego `flyway-database-postgresql` osobno**: Flyway 10+ (które Spring Boot 4 ciąga z BOM) zmodularyzował obsługę baz. Bez tego artifaktu Flyway rzuca `NoSuchMethodError` przeciw Postgresowi w runtime.

**Wersje**: NIE pinujemy — Spring Boot 4.0.6 BOM resolvuje wszystko. Zgodnie z istniejącą konwencją `pom.xml`.

**NIE dodajemy**: `spring-boot-starter-data-jpa`, encji, repozytoriów. To Faza C — odłożone świadomie.

### 2. `src/main/resources/application.properties` — datasource + HikariCP + Flyway

Dopisać na końcu (po sekcji actuator z Fazy A):

```properties
# --- Datasource (Railway service-link injects PG* vars) ---
spring.datasource.url=jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
spring.datasource.username=${PGUSER}
spring.datasource.password=${PGPASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver

# HikariCP pool sized for 512MB Hobby tier + Postgres max_connections~100
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.connection-timeout=10000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000

# --- Flyway ---
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=false
spring.flyway.validate-on-migrate=true
spring.flyway.fail-on-missing-locations=true
```

**Świadome wybory (do zmiany jeśli użytkownik widzi inaczej)**:
- `maximum-pool-size=5` zamiast Hikari default 10 — przy 11MB per connection × 10 ≈ 110MB tylko na idle pool, na 512MB tier to za dużo. 5 daje ~55MB. Dla single-owner traffic z PRD może wystarczyć nawet 3, ale 5 buforuje na concurrent dashboard requests.
- `minimum-idle=2` — dwie ciepłe konekcje honorują "1s ack" NFR, cold-connect to ~150ms TLS handshake.
- `baseline-on-migrate=false` — zaczynamy z pustym DB, V1 to pierwsza migracja. Ustawienie `true` maskowałoby przyszłe "zapomniałem migracji" bugi.
- `fail-on-missing-locations=true` — fail-fast jeśli `db/migration` zostało dockerignorowane przez przypadek.

**Wzór `${PGHOST}` ten sam co `infrastructure.md:148-152`** — placeholder rezolwuje się z env varów Railway (po service-link). Identyczny wzór działa lokalnie z Docker Postgres (sekcja "Lokalna weryfikacja").

### 3. `src/main/resources/db/migration/V1__init.sql` (nowy plik) — smoke-test migracja

```sql
-- V1: smoke-test migration. Proves Flyway connected, migrated, recorded history.
-- No domain entities yet (Phase B scope: deploy wiring only).
CREATE TABLE deploy_smoke_test (
    id          BIGSERIAL PRIMARY KEY,
    deployed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    note        TEXT NOT NULL
);

INSERT INTO deploy_smoke_test (note) VALUES ('Phase B initial deploy');
```

**Dlaczego nie pusty plik / `SELECT 1`**: Flyway loguje empty migration jako warning i zapisuje row w `flyway_schema_history` — działa, ale weryfikacja jest trudniejsza. `CREATE TABLE` + `INSERT` daje trywialny `\dt` + `SELECT *` test który pokazuje że całe wiring działa. Tabela zostanie zdropnięta w Fazie C kiedy pojawi się pierwsza encja domeny (np. `Location`).

**Backward-compatibility z poprzednim image'em** (Faza A): poprzednia wersja appki w ogóle nie zna tej tabeli, więc rollback `railway redeploy <Phase-A-deployment>` jest bezpieczny — stara aplikacja po prostu ignoruje istnienie `deploy_smoke_test`. To wzorzec na każdą przyszłą migrację (codified w sekcji Rollback).

### 4. `src/test/resources/application.properties` (nowy plik) — test profile isolation

```properties
spring.application.name=garageops
server.port=0

# Disable datasource & Flyway for the smoke test — no domain code uses them yet.
spring.autoconfigure.exclude=\
  org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
  org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration

management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

**Dlaczego**: po dodaniu datasource w pkt 2, `GarageopsApplicationTests.contextLoads()` próbowałby się połączyć do `${PGHOST}` przy `mvnw verify` → undefined → context fail → CI red. Trzy realne opcje:
- Testcontainers — wymagałby Docker w CI, +30-60s na każdy build, overkill na no-op test.
- H2 in-memory — dialect mismatch z prawdziwym Postgresem ugryzie w Fazie C kiedy SQL stanie się postgres-specific.
- **Wykluczenie autoconfig na test classpath** ← wybrane.

Override przez `src/test/resources/application.properties` na test classpath dotykuje tylko `mvnw verify`, nie main appki. Wybór do zrewidowania w Fazie C — wtedy `@DataJdbcTest` slices z prawdziwym Postgresem przez Testcontainers będą warte ceny.

### 5. `.github/workflows/deploy.yml` (nowy plik) — CI/CD pipeline

```yaml
name: Deploy to Railway

on:
  push:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: deploy-${{ github.ref }}
  cancel-in-progress: false

jobs:
  deploy:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Build & test
        run: ./mvnw -B -ntp verify

      - name: Install Railway CLI
        run: npm install -g @railway/cli@latest

      - name: Confirm Postgres backups gate
        run: echo "::warning::Manual gate — confirm Postgres backups toggle is ENABLED in Railway dashboard before this deploy promotes to main."

      - name: Deploy
        env:
          RAILWAY_TOKEN: ${{ secrets.RAILWAY_TOKEN }}
        run: railway up --service garageops --ci --detach
```

**Świadome decyzje (do zmiany jeśli użytkownik woli inaczej)**:
- `mvnw verify` (nie `package -DskipTests`): CI jest jedynym miejscem gdzie testy lecą automatycznie. Pominięcie defeats purpose. Testy ~5s — taniej niż debug pomyłki.
- `--service garageops`: po dodaniu Postgresa projekt ma 2 serwisy; `railway up` bez `--service` zawiesi się na interactive prompt. Jawne pinowanie nazwy.
- `--ci`: wyłącza interactive prompty i ANSI codes (Railway CLI docs).
- `--detach`: workflow nie taił logów deploya. Railway dashboard pokaże status. **Alternatywa do rozważenia**: bez `--detach` workflow czeka na healthcheck (`healthcheckTimeout=300s`) i daje czerwony CI jeśli deploy fails. Wybór: `--detach` dla MVP (szybszy feedback w GitHub UI); zdjęcie w Fazie C kiedy chcemy hard-gate'a.
- `cancel-in-progress: false`: odwrotnie do typowej rady. Cancelowanie `railway up` mid-image-push zostawia half-uploaded deploy.
- "Confirm Postgres backups gate" step: advisory `::warning::` — Railway nie ma API by sprawdzić toggle programowo (na 2026-05). Prawdziwy enforcement to Human gate #2.
- Token: **project-scoped `RAILWAY_TOKEN`** wiąże CLI z konkretnym projektem bez `RAILWAY_PROJECT_ID` (token IS the binding, per Railway docs). **NIE używamy** `RAILWAY_API_TOKEN` (account-scoped, ryzyko cross-project).

### 6. `.gitignore` — dopisać dwie sekcje

Aktualnie zawiera `### Deploy secrets ###` z Fazy A. Dopisać na końcu:

```
### Railway CLI / agent state ###
.railway/
.mcp.json
```

**Dlaczego `.railway/`**: zawiera per-developer link state (project ID, service ID, environment). Nie jest sekretne, ale couples repo do jednej maszyny dewelopera. CI używa tokena, nie linku, więc nigdy nie potrzebuje tego folderu.

**Dlaczego `.mcp.json`**: jeśli `railway mcp install` zapisze konfig do repo (zamiast `~/.claude.json` user-global), zawiera referencję do `RAILWAY_API_TOKEN` (account-scoped). Nawet bez literalnej wartości tokena — sam plik mówi "ten user ma podpięte konto Railway", co w combination z public repo to information leak. Bezpiecznie: ignoruj, wymuszaj user-global setup.

### 7. `src/main/java/com/example/garageops/` — bez zmian

Faza B nie dodaje żadnego kodu Javy. To Faza C (pierwsza encja, controller, integration test). AGENTS.md hard rule "Package by feature" wejdzie w grę dopiero z `com.example.garageops.locations` lub podobnym.

### 8. `wiki.txt` — wciąż poza zakresem

Tak jak w Fazie A — modified, ale to osobna decyzja użytkownika, nie wpada do commitów Fazy B.

---

## Kroki wykonania

### A. Zmiany w kodzie (agent)

1. Edytuj `pom.xml` — dopisz 4 dependency po actuatorze (pkt 1 wyżej). Zachowaj tab indentation.
2. Edytuj `src/main/resources/application.properties` — dopisz blok datasource + Hikari + Flyway (pkt 2 wyżej).
3. Utwórz `src/main/resources/db/migration/V1__init.sql` (pkt 3 wyżej). Najpierw zweryfikuj, że `src/main/resources/db/migration/` nie istnieje (Glob).
4. Utwórz `src/test/resources/application.properties` (pkt 4 wyżej). Najpierw zweryfikuj, że `src/test/resources/` nie istnieje (default Spring Boot scaffold pomija tę ścieżkę).
5. Utwórz `.github/workflows/deploy.yml` (pkt 5 wyżej). Najpierw zweryfikuj, że `.github/` nie istnieje.
6. Edytuj `.gitignore` — dopisz sekcję `### Railway CLI / agent state ###` (pkt 6 wyżej).

### B. Lokalna weryfikacja przed deployem (agent + Docker)

Z risk registera #1: każda Vn musi przejść lokalnie przed commitem. Phase B V1 jest trywialne, ale ustalamy nawyk już teraz.

1. **Postgres w Dockerze**:
   ```powershell
   docker run -d --name garageops-pg `
     -e POSTGRES_PASSWORD=dev `
     -e POSTGRES_USER=dev `
     -e POSTGRES_DB=garageops `
     -p 5432:5432 `
     postgres:16
   ```

2. **Lokalny start aplikacji z PG env vars**:
   ```powershell
   $env:JAVA_HOME = "C:\Install\jdk-21.0.2\jdk-21.0.2"
   $env:PGHOST = "localhost"
   $env:PGPORT = "5432"
   $env:PGDATABASE = "garageops"
   $env:PGUSER = "dev"
   $env:PGPASSWORD = "dev"
   .\mvnw.cmd spring-boot:run
   ```

   Sygnatura zielonego startu w logach (wszystkie 8 linii muszą pojawić się w tej kolejności):
   1. `HikariPool-1 - Starting...` → `Start completed.`
   2. `Flyway Community Edition ... by Redgate`
   3. `Database: jdbc:postgresql://localhost:5432/garageops (PostgreSQL 16.x)`
   4. `Successfully validated 1 migration (execution time ...)`
   5. `Creating Schema History table "public"."flyway_schema_history" ...`
   6. `Migrating schema "public" to version "1 - init"`
   7. `Successfully applied 1 migration to schema "public", now at version v1`
   8. `Started GarageopsApplication in N.Ns`

3. **DB-side smoke test**:
   ```powershell
   docker exec -it garageops-pg psql -U dev -d garageops -c "SELECT * FROM flyway_schema_history;"
   docker exec -it garageops-pg psql -U dev -d garageops -c "SELECT * FROM deploy_smoke_test;"
   ```

4. **`mvnw verify`** (na zatrzymanej aplikacji — port 8080 zwolniony):
   ```powershell
   .\mvnw.cmd verify
   ```
   `GarageopsApplicationTests.contextLoads()` musi przejść. Jeśli context fail z błędem o DataSource — test profile (`src/test/resources/application.properties`) jest źle wczytany. Sprawdź czy plik istnieje na test classpath.

5. **Cleanup**:
   ```powershell
   docker stop garageops-pg
   docker rm garageops-pg
   ```

### C. Provisioning Postgresa na Railway (human gate + agent)

**Kolejność jest twarda — nie wolno tego pomieszać**:

1. **Human gate 5**: użytkownik upewnia się, że jest zalogowany do Railway CLI i ma linked projekt:
   ```powershell
   railway whoami
   railway status  # powinno pokazać 'garageops'
   ```

2. **Human gate 1**: użytkownik (lub agent po potwierdzeniu) provisioniuje Postgres:
   ```powershell
   railway add --database postgres
   ```
   Wybrać tę samą region co `garageops` service (Amsterdam). Po sukcesie w dashboardzie widać dwa serwisy: `garageops` i `Postgres`.

3. **Human gate 2 — CRITICAL, no skipping**: właściciel konta otwiera dashboard → Postgres → Settings → Backups → **Enable daily backups**. Verification: dashboard pokazuje "Backups: Daily" i pierwszą zaplanowaną datę.

4. **Bind `PG*` env vars do `garageops` serwisu przez service-link** (agent):
   ```powershell
   railway service garageops
   railway variables --set 'PGHOST=${{Postgres.PGHOST}}' `
                     --set 'PGPORT=${{Postgres.PGPORT}}' `
                     --set 'PGDATABASE=${{Postgres.PGDATABASE}}' `
                     --set 'PGUSER=${{Postgres.PGUSER}}' `
                     --set 'PGPASSWORD=${{Postgres.PGPASSWORD}}'
   ```
   `${{Postgres.VAR}}` to Railway service-link template — wartości rezolwują się w runtime, rotują automatycznie. **Weryfikacja**: `railway variables --service garageops` pokazuje 5 wpisów `PG*` z placeholder-rezolwerem (literalne wartości nie są wyświetlane — privacy).

5. **JAVA_OPTS env var** (jeśli jeszcze nie ustawiony z Fazy A — sprawdzić `railway variables --service garageops` na obecność `JAVA_OPTS`):
   ```powershell
   railway variables --set "JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
   ```

### D. Pierwszy deploy z DB (agent, ręczny `railway up`)

Pierwszy DB-aware deploy idzie ręcznie, żeby zminimalizować surface area na pierwszy crash-loop. Workflow CI/CD wchodzi w grę dopiero w kroku E po sukcesie tego.

1. **Commit zmian z sekcji A** (BEZ workflow file na razie — wyłącz CI dla tej iteracji):
   ```powershell
   git add pom.xml src/main/resources/application.properties src/main/resources/db/migration/V1__init.sql src/test/resources/application.properties .gitignore
   git commit -m "Add Postgres + Flyway (Phase B): driver, V1 smoke-test, Hikari sizing"
   ```

2. **`railway up`**:
   ```powershell
   railway up --service garageops
   ```
   Pierwszy deploy z DB może trwać 3-7 min (build + image push + JVM start + Flyway migracja). Tail logów:
   ```powershell
   railway logs --service garageops -n 200
   ```
   Szukaj 8-linijkowej sygnatury z sekcji B krok 2 + brak stack-trace'ów.

3. **DB-side weryfikacja przez `railway connect postgres`**:
   ```powershell
   railway connect postgres
   # W psql shellu:
   \dt
   SELECT * FROM flyway_schema_history;
   SELECT * FROM deploy_smoke_test;
   \q
   ```
   Oczekiwane: dwie tabele (`flyway_schema_history`, `deploy_smoke_test`), V1 row jako success, INSERT row obecny.

4. **HTTP smoke test**:
   ```powershell
   Invoke-RestMethod https://<railway-domain>/actuator/health
   ```
   Oczekiwane: `{"status":"UP"}`. **NIE** może zawierać `components.db` lub `details` (privacy guardrail).

5. **Memory regression check**: dashboard Railway → `garageops` → Metrics → idle memory < 450MB (Faza A była < 400MB; Hikari + JDBC + Flyway dorzucają ~40-50MB).

### E. CI/CD pipeline (human gate + agent)

Po zielonym D:

1. **Human gate 4**: właściciel konta tworzy `RAILWAY_TOKEN` jako project-scoped token (dashboard → Project → Settings → Tokens → Create New Token), kopiuje do GitHub repo Settings → Secrets → New repository secret → `RAILWAY_TOKEN`.

2. **Commit workflow file**:
   ```powershell
   git add .github/workflows/deploy.yml
   git commit -m "Add GitHub Actions deploy workflow (Phase B): auto-deploy on push to main"
   ```

3. **Push do `develop`** (NIE main — workflow odpala się tylko na main, najpierw chcemy zobaczyć że Actions w ogóle uruchamia syntax-check):
   ```powershell
   git push origin develop
   ```
   GitHub UI → Actions tab → workflow "Deploy to Railway" jest widoczny ale nie odpalił (correct — trigger to push do main).

4. **Manual test workflow_dispatch z develop** (opcjonalnie, sanity check): GitHub UI → Actions → Deploy to Railway → Run workflow → branch develop → Run. **UWAGA**: ten test deployuje develop do production Railway. Skip jeśli nie chcesz mutacji z develop branch. Lepsza opcja: skip i poleć od razu do kroku 5.

5. **Merge develop → main**:
   ```powershell
   git checkout main
   git merge --no-ff develop
   git push origin main
   ```
   GitHub Actions powinien odpalić automatycznie. Watch: Actions tab → "Deploy to Railway" → zielony status check w ~3-5 min.

6. **Verification że CI deploy zadeployował**: dashboard Railway → garageops → Deployments → najnowszy wpis ma timestamp z ostatniej minuty + status Active.

### F. Railway agent tooling install (agent, after E green)

Po zielonym deployu CI/CD — instalacja skills + lokalnego MCP. Order: **po** udanym Phase B żeby tooling miał z czym rozmawiać.

1. **Próba jednoshotu**:
   ```powershell
   railway setup agent -y
   ```
   Sprawdź wynik. Jeśli komenda nie istnieje (zależy od wersji CLI), fallback do kroku 2.

2. **Fallback — components osobno**:
   ```powershell
   railway skills install --agent claude-code
   railway mcp install --agent claude-code
   ```
   `railway mcp install` domyślnie używa lokalnego stdio (zgodnie z wybraną opcją z AskUserQuestion). NIE używać `--remote` w tej fazie.

3. **Weryfikacja**:
   - Skills lokowane w `~/.claude/skills/` (user-global). Sprawdź `ls ~/.claude/skills/` (PowerShell: `Get-ChildItem $env:USERPROFILE\.claude\skills\`) — powinny pojawić się Railway-related skills.
   - MCP config w `~/.claude.json` (user-global) — Read tool na ścieżce, szukaj sekcji `mcpServers` z wpisem `railway`.
   - **Privacy check**: `.mcp.json` w repo NIE może być stworzony. Jeśli się pojawił — natychmiast usuń i przenieś wpis do `~/.claude.json`. (`.gitignore` ma `.mcp.json` od kroku A.6, więc commit jest bezpieczny, ale wolimy żeby plik w ogóle nie istniał w repo.)
   - Restart Claude Code w tym katalogu — nowe skills i MCP serwer powinny być widoczne. `/mcp` w Claude Code pokaże listę dostępnych MCP serwerów; szukaj `railway`.

4. **Smoke test MCP**: w Claude Code zapytaj agenta o "list my Railway projects" lub "show garageops service status". Odpowiedź powinna przyjść z MCP toola, nie z CLI'a wrapowanego w bash.

5. **Konflikt skills repo-vs-user**: nie ma konfliktu — repo `.claude/skills/` ma priorytet nad user-global w Claude Code, więc Railway skills dosłownie dopisują się jako dodatkowe (nie nadpisują repo-local).

### G. Commit i archiwum planu

Po zielonej Verification — dwa commity (już wykonane w D.1 i E.2). Plus jeden commit z archiwum planu:

```powershell
git add context/deployment/deploy-plan-phase-B.md
git commit -m "Archive Phase B deploy plan"
```

---

## Verification (definicja zielonej Fazy B)

Wszystkie dziesięć musi przejść:

1. **Lokalny start z Docker Postgres** loguje 8-linijkową sygnaturę z sekcji B krok 2.
2. **`mvnw verify`** zielony lokalnie i w GitHub Actions (test profile wyklucza DataSource autoconfig).
3. **`/actuator/health` na Railway** zwraca `{"status":"UP"}` bez `components.db` ani `details`.
4. **GitHub Actions run** zielony na merge do `main` w ~3-5 min — krok "Deploy" pokazuje `Deployment live`.
5. **Railway logs** (`railway logs --service garageops -n 200`) pokazują wszystkie Flyway/Hikari linie + `Started GarageopsApplication`.
6. **DB state**: `railway connect postgres` → `\dt` pokazuje `flyway_schema_history` i `deploy_smoke_test`. `SELECT count(*) FROM flyway_schema_history WHERE success=true;` zwraca 1.
7. **Memory under 450MB** w dashboardzie Railway na idle (regression check vs Faza A < 400MB; Hikari + JDBC + Flyway dorzucają ~40-50MB).
8. **Latency NFR**: 5 ciepłych `Invoke-RestMethod` na `/actuator/health` — średnia < 1000ms (regression check vs Faza A).
9. **Railway agent tooling**: `/mcp` w Claude Code pokazuje serwer `railway`, smoke test query odpowiada z MCP toola.
10. **Backups confirmed**: dashboard → Postgres → Settings → Backups status = "Enabled" + last successful snapshot timestamp widoczny.

---

## Rollback

Trzy ścieżki, kolejność od najlżejszej:

1. **Cofnięcie deploya na Railway** (problem w runtime po deployu, schema już zaaplikowana):
   ```powershell
   railway status                  # zidentyfikuj poprzedni deployment ID (Faza A image)
   railway redeploy <deployment-id>
   ```
   **Bezpieczne dla Fazy B**: V1 jest purely additive (CREATE TABLE + INSERT), więc poprzedni image (Faza A bez DB świadomości) działa OK przeciw V1-migrated DB — ignoruje istnienie `deploy_smoke_test`. To wzorzec na każdą przyszłą migrację: **każda Vn musi być backward-compatible z previous deployed image** (codified jako lesson na koniec — pkt "Co poza zakresem").

2. **Wycofanie zmian z repo** (problem w kodzie, Railway już zdeployował broken stan):
   ```powershell
   git revert <commit-hash>
   git push origin main
   ```
   GitHub Actions automatycznie odpali deploy poprzedniego stanu. Jeśli rollback dotyczy migracji — UWAGA: schema NIE wraca, tylko kod. Trzeba ręcznie:
   ```powershell
   railway connect postgres
   # W psql:
   DROP TABLE deploy_smoke_test;
   DELETE FROM flyway_schema_history WHERE version = '1';
   ```
   Albo zaakceptować "schema wyprzedza kod" i przyspieszyć fix-forward.

3. **Pełna deinstalacja Postgresa** (jeśli DB się skorumpuje i restore from backup nie wystarczy):
   - Dashboard Railway → Postgres service → Settings → Delete Service. **Human-only action** (nie agent).
   - Następnie odpiąć service-link env vars z `garageops` (automatycznie zresetowane po DELETE Postgres service).
   - Re-provision: `railway add --database postgres` od nowa, restore z backupu w dashboardzie (jeśli enable'owałeś backups).
   - Czas: ~10 min + restore time. **Jeśli backups były DISABLED** — kompletna utrata danych. To dlaczego Human gate 2 jest CRITICAL.

**Co NIE wraca automatycznie**: schema migrations (codified z Fazy A risk register). W Fazie B V1 jest trywialne, więc ręczny rollback to jedna komenda. W Fazie C+ — Flyway/Liquibase backward-compatible discipline jest twarda zasada.

---

## Co JEST poza zakresem tego planu

Wprost odraczamy do następnych planów / lessons, żeby nie rozszerzać Fazy B:

- **Encje domeny**: Location, Garage, Tenant, Contract, Payment z PRD — Faza C.
- **JPA / Spring Data**: `spring-boot-starter-data-jpa` dependency, `@Entity` / `@Repository` / `JpaRepository<>` interfaces. Faza C.
- **Spring Security**: FR-001/FR-002 email+password auth — Faza D (auth implementacja, nie infrastruktura).
- **PR Preview Environments**: po dodaniu Postgresa to twarde NIE bez separate per-env DB. Odłożone do Fazy E lub porzucone.
- **Testcontainers w CI**: pierwsza encja w Fazie C dostanie integration test slice z Testcontainers — wtedy zmieni się test profile z autoconfig-exclude na real-Postgres.
- **MCP Railway remote/OAuth tryb**: lokalny stdio wystarczy do Fazy B-C. Remote ma sens kiedy chcesz dzielić agent-state z innym deweloperem.
- **`railway whoami` jako CI guard**: opcjonalne ulepszenie workflow w Fazie C, +2s na auth-loud-fail jeśli token wygasł.
- **`--detach`-off w workflow**: zdjąć dopiero kiedy chcesz hard-gate'a (deploy fail = CI red). Dla MVP solo dev `--detach` daje szybszy feedback w GitHub UI.
- **Lesson o backward-compatible migrations**: po Fazie B warto uruchomić `/10x-lesson` i utrwalić "każda Vn musi być backward-compatible z previous deployed image" w `context/foundation/lessons.md`. Nie wpisuję tego w plan, bo to meta-task.
- **CLOUD Act / privacy posture**: prawne, nie infrastrukturalne — `infrastructure.md` risk register już to flagował.
- **`wiki.txt` modified**: osobna decyzja użytkownika, nie wpada do commitów Fazy B (tak jak w Fazie A).

---

## Critical files dla implementacji

| File | Action |
|---|---|
| `C:\Workspaces\WorkspacesTraining\garage\garage\pom.xml` | Edit — dodać 4 dependency po `spring-boot-starter-actuator` |
| `C:\Workspaces\WorkspacesTraining\garage\garage\src\main\resources\application.properties` | Edit — dopisać blok datasource + Hikari + Flyway |
| `C:\Workspaces\WorkspacesTraining\garage\garage\src\main\resources\db\migration\V1__init.sql` | Create |
| `C:\Workspaces\WorkspacesTraining\garage\garage\src\test\resources\application.properties` | Create (test classpath override) |
| `C:\Workspaces\WorkspacesTraining\garage\garage\.github\workflows\deploy.yml` | Create |
| `C:\Workspaces\WorkspacesTraining\garage\garage\.gitignore` | Edit — dopisać sekcję `### Railway CLI / agent state ###` |
