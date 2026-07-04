# Local-dev owner credentials (access-control-foundation, F-01)

These are the **local-dev fallback** credentials baked into `application.properties` as the
default of the `OWNER_USERNAME` / `OWNER_PASSWORD_HASH` env vars. They are **not** a real secret
— they exist only so the app boots and the gating contract can be exercised without external
config. In any deployed environment, set the `OWNER_*` env vars (see Migration Notes in `plan.md`).

| Field | Value |
| --- | --- |
| Username | `owner` |
| Password (plaintext) | `owner-local-dev` |
| Password (BCrypt hash) | `$2a$10$QMGr6Q3SaPmUEkg6/ukov.oRuLjMXe502Lj5WHIgrWAi/dGcBh26a` |

The hash and plaintext are a **matched BCrypt pair**, generated together with Spring's own
`new BCryptPasswordEncoder().encode("owner-local-dev")` and verified (`matches` → `true`).

> ⚠️ Phase 2's `SecurityGatingTests` login case signs in with the plaintext `owner-local-dev`.
> If you regenerate the hash, regenerate the pair and update this note **and** the test, or the
> test fails with a misleading "bad credentials".
