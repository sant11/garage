# Plan: Pierwsze wdrożenie GarageOps na Railway (Phase A)

## Context

Repo GarageOps (Spring Boot 4.0.6 / Java 21 / Maven, branch `develop`) ma w `context/foundation/infrastructure.md` zatwierdzoną decyzję platformową: **Railway**, region Amsterdam, managed Postgres co-located, Hobby tier (~$5/mo). Brakuje pierwszego zielonego deploya.

Wcześniejszy commit `d33a9ab "Add Railway deploy gates"` próbował tę samą drogę i został cofnięty w `d31c730` — pliki zniknęły z drzewa, ale jego intencja (deploy Spring Boot **bez bazy danych**, żeby zawęzić powierzchnię awarii pierwszego uruchomienia) jest sensowna i odtwarzamy ją tutaj z trzema poprawkami: użycie projektowego `mvnw` zamiast obrazu `maven:3.9`, tuning JVM przeniesiony z Dockerfile do Railway env var (konfigurowalny bez rebuildu), `railway.json` z explicit `builder=DOCKERFILE` (eliminuje Railpack/Spring Boot 4 z risk registera "Spring Boot 4 + Java 21 build fails on Railpack auto-detect").

**Cel tego planu**: zielony deploy działającej aplikacji Spring Boot na Railway z healthcheckiem `/actuator/health`, bez bazy danych, bez CI/CD. To "Phase A" — minimalna powierzchnia awarii, weryfikuje że Maven build + Dockerfile + ekspozycja `$PORT` + actuator + healthcheck działają end-to-end na Railway. Wszystko inne (Postgres, Flyway, Spring Security, GitHub Actions, PR Previews, MCP) odraczamy do osobnych planów.

**Decyzje już potwierdzone z użytkownikiem (sesja 2026-05-25)**:
- Zakres: aplikacja **bez** Postgres w tym deployu
- Build path: **Dockerfile multi-stage**, builder w `railway.json` ustawiony na `DOCKERFILE` (nie Railpack)
- CI/CD odroczone, deploy ręcznym `railway up`
- Backups: świadomie poza zakresem Phase A (nie ma DB), wraca jako twardy gate w Phase B
- Plik istniejący jako baseline — minor refinements (healthcheckTimeout 60→300, jawna sekcja Rollback i Human Gates)

---

## Human gates (must-do przez użytkownika, agent NIE ma dostępu)

Agent może edytować repo i wołać `railway` CLI (po lokalnym uwierzytelnieniu użytkownika), ale następujące akcje **muszą zostać wykonane ręcznie przez właściciela konta**:

1. `npm install -g @railway/cli` i `railway login --browserless` (uwierzytelnienie do konta Railway).
2. `railway init` — wybór nazwy projektu (`garageops`) i regionu (`Europe West (Amsterdam)` — jedyny EU region).
3. Dashboard Railway → świadome **NIE-akcje** (z risk registera, ustawiamy je jako nawyk już teraz):
   - **NIE włączać** PR Preview Environments (dziedziczą sekrety produkcji — w Phase A nie ma DB, ale wraca w Phase B jako risk).
   - **NIE włączać** App Sleeping (cold start JVM 5-15s łamie NFR z PRD: "1-sekundowy ack na CRUD i dashboard").
   - **NIE włączać** Backups — w Phase A nie ma DB do backupu. To wraca jako CRITICAL gate w Phase B.
4. Po deployu — wygenerowanie publicznego URL przez `railway domain` lub kliknięcie "Generate Domain" w dashboardzie (jeśli CLI nie wystarczy).
5. Przegląd planu i akceptacja przed `railway up`.

Wszystkie inne kroki (edycja plików, lokalny build, `railway up`, `railway logs`, smoke testy HTTP) wykonuje agent.

---

## Pliki do utworzenia / zmiany

Wszystkie zmiany w korzeniu `C:\Workspaces\WorkspacesTraining\garage\garage\`. Wzór z cofniętego commita `d33a9ab` (sprawdzony przez `git show d33a9ab`) z poprawkami wymienionymi w **Context**.

### 1. `pom.xml` — dodać Spring Boot Actuator

Dependency dopisać **bezpośrednio po** `spring-boot-starter-webmvc`, zgodnie z konwencją AGENTS.md (Spring Boot 4 używa `-webmvc`, NIE `-web`):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**NIE** dodajemy `org.postgresql:postgresql` ani `spring-boot-starter-data-jpa` — to Phase B.

### 2. `src/main/resources/application.properties` — port, graceful shutdown, healthcheck

Plik aktualnie zawiera tylko `spring.application.name=garageops`. Dopisać:

```properties
server.port=${PORT:8080}
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s

management.endpoints.web.exposure.include=health
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=never
```

`show-details=never` wymagany przez guardrail prywatności z PRD (cofnięty commit message argumentował tak samo). `${PORT:8080}` daje fallback dla lokalnego dev na 8080, ale na Railway nadpisuje go platforma.

### 3. `Dockerfile` (nowy plik w korzeniu) — multi-stage build z `mvnw`

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
```

Wzorzec multi-stage `eclipse-temurin:21-jdk → :21-jre` z `infrastructure.md` (sekcja Fly.io). Użycie `./mvnw` zamiast obrazu `maven:3.9` pinuje wersję Mavena przez wrapper w repo. `exec` w ENTRYPOINT przekazuje sygnały (SIGTERM) procesowi JVM, co spina się z `server.shutdown=graceful`. `$JAVA_OPTS` jest pusty domyślnie; Railway ustawimy go env varem (krok B.4).

### 4. `.dockerignore` (nowy plik w korzeniu)

```
target/
.git/
.idea/
.vscode/
.mvn/wrapper/maven-wrapper.jar
*.iml
HELP.md
README.md
context/
.env
.env.*
*.local.*
*.local.properties
application-local.properties
application-*-local.properties
```

Wykluczamy `context/` (foundation docs nie lecą do obrazu), env-files (sekrety nie lecą do obrazu) oraz IDE-junk. `.mvn/wrapper/maven-wrapper.jar` jest wykluczony, bo `mvnw` go pobiera przy starcie — eliminuje stale-cache.

### 5. `railway.json` (nowy plik w korzeniu)

```json
{
  "$schema": "https://railway.com/railway.schema.json",
  "build": { "builder": "DOCKERFILE", "dockerfilePath": "Dockerfile" },
  "deploy": {
    "healthcheckPath": "/actuator/health",
    "healthcheckTimeout": 300,
    "restartPolicyType": "ON_FAILURE",
    "restartPolicyMaxRetries": 3
  }
}
```

`builder=DOCKERFILE` eliminuje Railpack z drogi (mitigacja risk: "Railpack docs lag for Spring Boot 4"). `healthcheckTimeout=300` jest świadomym wyborem zamiast 60 — JVM cold start na 512MB Hobby tier potrafi zająć 30-60s przy pierwszym requeście, a `healthcheckTimeout` w Railway to deadline na ready, nie per-probe timeout. 300s daje margines bez maskowania prawdziwych awarii (po 5 minutach naprawdę coś jest złe). `restartPolicyMaxRetries=3` chroni przed loopem OOM (mitigacja risk: "$5 floor exceeded silently via OOM-restart loop").

### 6. `.gitignore` — dopisać exclusions na sekrety

Aktualny `.gitignore` (sprawdzone) nie zawiera env-files. Dopisać sekcję na końcu:

```
### Deploy secrets ###
.env
.env.*
*.local.properties
application-local.properties
application-*-local.properties
```

### 7. `wiki.txt` — poza zakresem

Widnieje w `git status` jako modified (binarka). NIE wchodzi w ten commit — zostaje do osobnej decyzji użytkownika.

---

## Kroki wykonania

### A. Zmiany w kodzie (agent)

1. Edytuj `pom.xml` — dodać dependency `spring-boot-starter-actuator` po `spring-boot-starter-webmvc`.
2. Edytuj `src/main/resources/application.properties` — dopisać 7 linii (port, graceful shutdown, actuator health probes).
3. Utwórz `Dockerfile`, `.dockerignore`, `railway.json` w korzeniu repo.
4. Edytuj `.gitignore` — dopisać sekcję `### Deploy secrets ###`.
5. **Lokalna weryfikacja przed deployem**:
   ```powershell
   $env:JAVA_HOME = "C:\Install\jdk-21.0.2\jdk-21.0.2"
   .\mvnw.cmd verify
   ```
   - `GarageopsApplicationTests` musi przejść (kompiluje się z nowym actuator dependency).
   - Jar musi pojawić się w `target/`.
   - Manualny test actuatora:
     ```powershell
     $env:PORT="8080"; java -jar target\garageops-0.0.1-SNAPSHOT.jar
     # W drugim terminalu:
     Invoke-RestMethod http://localhost:8080/actuator/health
     # Oczekiwane: {"status":"UP"} — bez "details" / "components"
     ```

### B. Setup konta Railway (użytkownik — human gate)

Patrz sekcja **Human gates** powyżej. Po wykonaniu kroków 1-3 z Human gates, **przed `railway up`** ustawić jeszcze:

4. **JAVA_OPTS jako env var serwisu** (mitigacja: OOM-restart loop drenujący kredyt):
   ```powershell
   railway variables --set "JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
   ```
   - `MaxRAMPercentage=75.0` na 512MB → heap ~384MB, ~128MB na metaspace + native + system. Bezpieczny margines.
   - `UseG1GC` zgodnie z rekomendacją infrastructure.md "Getting Started" krok 6. (Alternatywa: `UseSerialGC` z cofniętego commita — niższe overhead pamięci, ale wyższe pauzy GC. Trzymamy G1GC; jeśli OOM-restart wróci, podmiana SerialGC jest jednolinijkową zmianą env vara.)

### C. Pierwszy deploy (agent)

Po Human gates 1-3 i B.4:

1. Z korzenia repo:
   ```powershell
   railway up
   ```
   Railway zbuduje obraz z naszego Dockerfile (bo `railway.json` ma `builder=DOCKERFILE`), wypchnie go i uruchomi serwis. Pierwsze uruchomienie może trwać ~2-5 min (build + image push + JVM start).

2. Po deploy — wygeneruj publiczny URL:
   ```powershell
   railway domain
   ```
   Jeśli CLI nie wystarczy — user klika "Generate Domain" w dashboardzie (Human gate 4).

### D. Verification (agent, end-to-end)

Wszystkie cztery muszą przejść — to definicja zielonego pierwszego deploya:

1. **Logi startu**:
   ```powershell
   railway logs --service garageops -n 200
   ```
   Szukaj: `Started GarageopsApplication in N seconds`. Brak stack-trace'ów, brak loopów restartu.

2. **HTTP smoke test**:
   ```powershell
   Invoke-RestMethod https://<railway-domain>/actuator/health
   ```
   Oczekiwany wynik: `{"status":"UP"}`. **NIE** może zawierać `components` / `details` (potwierdza `show-details=never` aktywny — privacy guardrail z PRD).

3. **Status serwisu w dashboardzie**: `garageops` ma `Active`, healthcheck zielony, memory usage poniżej 400MB w spoczynku (margines do 512MB Hobby tier).

4. **NFR z PRD: 1-sekundowa odpowiedź na ciepłym serwisie**:
   ```powershell
   1..5 | ForEach-Object { Measure-Command { Invoke-RestMethod https://<railway-domain>/actuator/health } | Select-Object TotalMilliseconds }
   ```
   Średnia z 5 prób `< 1000ms` (po pierwszym requeście, który może być wolniejszy przez cold-start LB). Jeśli stałe `> 1000ms`: zapisać jako known limitation w `context/foundation/lessons.md` (cold-start vs warm-start dyskusja należy do Phase B z prawdziwymi endpointami CRUD, nie do actuatora).

### E. Commit

Po zielonej Verification — **jeden** commit obejmujący zmiany z sekcji A:

```
Add Railway deploy gates (Phase A): actuator, Dockerfile, railway.json
```

Treść w stylu cofniętego `d33a9ab` (krótki imperatyw, brak Conventional Commits — AGENTS.md tego nie wymaga). `wiki.txt` NIE wchodzi do tego commita.

---

## Rollback

Trzy ścieżki w kolejności od najlżejszej:

1. **Cofnięcie deploya na Railway** (po zielonym deployu pojawi się błąd w runtime):
   ```powershell
   railway status                  # zidentyfikuj poprzedni deployment ID
   railway redeploy <deployment-id>
   ```
   W Phase A nie ma poprzedniego deploya, więc ta opcja zaczyna obowiązywać dopiero od **drugiego** deploya. Czas-do-rewersji: ~30-60s.

2. **Wycofanie zmian z repo** (jeśli problem w kodzie, a Railway już zdeployował broken stan):
   ```powershell
   git revert <commit-hash>
   railway up
   ```
   Wzorzec z `d31c730 Revert "Add Railway deploy gates"` — ten plan jest właśnie odpowiedzią na tamten revert.

3. **Pełna deinstalacja** (jeśli deploy nigdy nie wystartuje i zalewa kredyt):
   - Dashboard Railway → Service → Settings → Delete Service. **Human-only action** (nie agent).
   - Następnie usunąć projekt: dashboard → Project → Settings → Delete Project.
   - Czas: ~2 min. Konto zachowuje historię, ale kredyt $5 jest tym scenariuszem ochroniony.

**Co NIE wraca automatycznie** (uwaga z infrastructure.md): migracje schemy DB nie rollbackują się — irrelevant w Phase A (no DB), ale kluczowe w Phase B (Flyway/Liquibase muszą być backward-compatible).

---

## Co JEST poza zakresem tego planu

Wprost odraczamy do następnych planów / lekcji, żeby nie rozszerzać pierwszego deploya:

- **PostgreSQL**: `railway add --database postgres`, sterownik w `pom.xml`, `spring.datasource.*` bindowane do `PG*` env vars (NIE `DATABASE_URL` w postaci `postgresql://` — patrz infrastructure.md krok 5).
- **Migracje**: Flyway lub Liquibase (wybór z risk registera: "schema migrations do not roll back automatically").
- **Backups**: **CRITICAL** day-one toggle w dashboardzie po prowizji Postgresa (risk register: likelihood High, impact Catastrophic).
- **Spring Security**: FR-001/FR-002 email+password auth — implementacja, nie infrastruktura.
- **GitHub Actions**: workflow `.github/workflows/deploy.yml` z `railway up --detach` na push do `main`, `RAILWAY_TOKEN` jako per-project GitHub secret (NIE account-scoped — risk register).
- **MCP Railway**: opcjonalny krok 11 z infrastructure.md — wymaga decyzji o stałym dostępie agenta do read-only state Railway.
- **PR Preview Environments**: wymagają osobnej konfiguracji Postgres na środowisko (risk register: "PR Preview points at production Postgres").
- **CLOUD Act / privacy posture**: dokumentacja prawna, nie infrastrukturalna.
- **`wiki.txt` modified**: osobna decyzja użytkownika, nie wpada do commita Phase A.
