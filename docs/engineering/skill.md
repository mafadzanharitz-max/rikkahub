# CIPHER — RikkaHub Engineering Skill

**Purpose:** This is the operational playbook for CIPHER when researching, maintaining, hardening, and releasing the RikkaHub ARMv7 fork. Read this file at the start of every new or resumed RikkaHub work session, then inspect the live repository state. It is a project guide, not proof that any implementation or test is complete.

## 1. Authority, boundaries, and project identity

- Repository: `mafadzanharitz-max/rikkahub`. Confirm the active branch and HEAD before every write.
- Product identity: **RikkaHub**. The ARMv7 fork is still RikkaHub, not a successor product. For SIWC `agent_name_hint`, use the owner-approved exact identity **RikkaHub ARMv7**.
- Target: Android `armeabi-v7a`, minimum Android API 27, package ID `me.rerere.rikkahub`.
- The owner works from a low-spec Android phone only. Never make the phone the build machine and never ask the owner to run installs, compiles, heavy tests, or long diagnostic commands locally. Use authorized remote repository and CI capabilities.
- The owner has authorized remote repository work, including code changes and CI, while expecting careful control of Actions usage and cost. Do not run CI for documentation-only edits or speculative one-line experiments. Batch related changes and validate before spending a run.
- Preserve the established release signing identity and signing secrets. Never request, print, commit, or expose passwords, private keys, keystore bytes, tokens, or secret values.
- Do not revive unrelated retired projects or introduce branch sprawl. Keep work on the active project branch unless repository state or an explicit instruction requires otherwise.

## 2. Non-negotiable engineering behavior

1. **Inspect before editing.** Read the current branch, HEAD SHA, relevant files, callers, tests, dependency versions, workflow, and recent history. Do not trust old summaries over live source.
2. **Research before protocol work.** For external services, authentication, Android platform behavior, Gradle/AGP, GitHub Actions, and security requirements, consult current primary documentation and relevant upstream source. Record the URL, document/version/date checked, and the exact requirement that affects the code.
3. **Trace the whole path.** Follow data from UI to persistence, networking, background work, backup/restore, logging, and tests. Search for all call sites and legacy fallbacks before changing a contract.
4. **Design before patching.** State invariants, lifecycle/race behavior, failure handling, compatibility impact, and test cases before implementing complex changes.
5. **Prefer integrated, minimal changes.** Group dependent changes into a coherent patch. Avoid speculative refactors, duplicate abstractions, unrelated cleanup, and commits made only to trigger CI.
6. **Review the diff.** Check formatting, imports, nullability, concurrency, cancellation, API compatibility, secrets, backup boundaries, and unintended file changes. Re-read the final source after writing.
7. **Test proportionally.** Run targeted static checks or tests where available; use the Release CI gate only after the integrated patch is ready. A successful compile is not proof that behavior, signing, ABI, or runtime compatibility is correct.
8. **Diagnose failures from evidence.** Capture the exact failed job, step, task, test name, assertion, and relevant log. Identify the first causal failure, not merely the final workflow status. Reproduce or reason from the failing code path before changing code.
9. **Never conceal uncertainty.** Label facts as verified, inferred, or unknown. Never claim a fix, test, build, signing check, upload, or release succeeded unless the corresponding evidence was inspected.
10. **Keep a failure ledger.** For each meaningful regression, record commit/run, first failing task, root cause, corrective change, and regression test. Do not repeat a known failure without explaining why the new attempt is materially different.

## 3. Research and source-quality protocol

Use internet research whenever facts may have changed or the implementation depends on an external contract.

Source priority:
1. Official product/developer documentation, standards (RFCs), platform documentation, and published terms.
2. Official upstream source code, release notes, and maintainer issue/PR discussions.
3. Reproducible tests, authoritative vendor responses, and established technical references.
4. Community reports (including forums/Reddit) only as supporting evidence, never as sole proof of a security or protocol requirement.

For every important external requirement:
- Confirm the exact endpoint, method, headers, payload, response fields, scopes, claims, supported feature set, and error semantics from the current source.
- Check whether the document is preview/experimental and record its date/version.
- Distinguish a documented guarantee from an observed behavior or assumption.
- Check upstream changes and deprecations before pinning versions or APIs.
- Never copy credentials, personal data, or secret-bearing logs into searches, issues, commits, or documentation.
- If sources conflict, prefer the newest authoritative specification and explain the conflict; do not silently choose.

## 4. Android ARMv7 compatibility

- Preserve `armeabi-v7a` packaging and Android API 27 compatibility unless the owner explicitly changes the target.
- Inspect ABI filters, native libraries, ELF class/machine, NDK/CMake settings, minSdk, desugaring, Java/Kotlin bytecode targets, and transitive AAR/JNI dependencies when changing build or dependency configuration.
- Check that new APIs have API-27-safe alternatives or correct runtime guards. A successful modern CI build does not prove behavior on Android 8.1.
- Treat APK size and startup/runtime constraints as first-class concerns for the low-spec target device.
- Never remove a dependency or native binary based only on filename or size; trace runtime use and validate the resulting APK contents.

## 5. Build and CI policy

- Routine pipeline builds **Release APK only**.
- Keep unit tests and Release quality gates.
- Do not routinely assemble, validate, or upload Debug APK. Build Debug only when a concrete serious bug requires a diagnostic build.
- Preserve Release checks for APK existence, package identity, ARMv7 ELF/ABI, signing certificate identity, and artifact upload.
- Reuse the verified successful baseline as a comparison point, but re-check its exact commit and workflow before relying on it.
- Avoid redundant Actions runs, matrix expansion, duplicate jobs, and CI changes that do not improve confidence.
- Before dispatching CI, confirm the intended commit is on the intended branch and all related changes are integrated.
- After CI, inspect run conclusion, all required jobs/steps, test counts/failures, APK metadata, signing verification, and artifact details. Report wall-clock duration separately from billed runner minutes; do not infer cost savings from elapsed time.

## 6. SIWC / ChatGPT integration — critical security gate

Treat Sign in with ChatGPT (SIWC) as a complete authentication **and** inference-transport contract. An OAuth login that happens to return a token is not sufficient.

- Follow current official SIWC documentation and Terms. Do not reuse undocumented Codex CLI credentials, ChatGPT web `backend-api` routes, private session headers, or quota endpoints as a substitute for SIWC.
- For first registration, use the documented `dynamic_agent_client` authorization flow with the approved `agent_name_hint=RikkaHub ARMv7` and a stable per-installation `ext_agent_host_id`. Do not invent a generic Dynamic Client Registration endpoint.
- Capture and persist the issued client ID returned by the registration callback. Use that issued ID for code exchange and subsequent sign-ins for that registration; never exchange using `dynamic_agent_client`.
- Returning sign-in must reuse the saved registration/client binding. Keep account registrations distinct; reject mismatched callback client IDs and account subjects.
- Generate fresh cryptographically random state, nonce, and PKCE verifier/challenge for every attempt. Use the exact loopback callback URI `http://127.0.0.1:<port>/auth/callback` in both authorization and code exchange. Do not substitute `localhost`.
- Verify ID-token signature using OpenAI JWKS with key rotation support, then validate issuer, audience (issued client ID), expiry, nonce, non-empty subject, and account binding before saving credentials.
- Enable plan inference only when the **token response's granted scopes** include `chatgpt.tokens.use.direct`. Callback scope text is not authoritative.
- Refresh using the issued client ID and documented resource; serialize refresh-token rotation and never lose the newest refresh token. Sign-out must coordinate with in-flight refresh, attempt documented revocation, clear local tokens even when remote revocation cannot be confirmed, and report that uncertainty.
- Use documented public model discovery `https://api.openai.com/v1/models` and inference `https://api.openai.com/v1/responses`. Set `store: false`, `stream: true`, send the complete local context, and remove Codex-only endpoints, headers, and undocumented fallbacks.
- Apply the current SIWC preview capability restrictions. Explicitly disable unsupported request fields and tools; do not silently forward arbitrary custom bodies or unsupported capabilities.
- Keep tokens and per-host identity local and user-controlled. Exclude them from portable backup, sync, analytics, logs, diagnostics, crash reports, and unrelated automation. Never pool, transfer, resell, share, or expose a general-purpose API for another user's plan.
- If any protocol, transport, terms, or security requirement remains unverified, mark SIWC **BLOCKED** and do not claim compliance or release readiness.

Known project reference: `docs/security/SIWC_COMPATIBILITY_AUDIT.md`. Re-read it, then verify every statement against current official SIWC sources before implementation; an audit is a snapshot, not a substitute for current research.

## 7. Security and data lifecycle

For every credential or identity field, map creation, validation, storage, update, rotation, logout, backup, restore, export, logging, and deletion. Use least privilege and fail closed on identity or integrity checks. Keep signing material outside the repository and CI logs. Do not weaken TLS validation, JWT validation, Android permissions, or backup sanitization to make a test pass.

## 8. Concurrency and lifecycle review

For login, refresh, logout, provider deletion, account switching, and settings edits:
- Identify the owner of each coroutine/job, mutex, generation counter, and persisted state transition.
- Define what happens when cancellation or logout occurs while network I/O is in flight.
- Ensure stale results cannot overwrite newer state.
- For rotating credentials, preserve the newest server-issued token long enough to revoke or safely persist it; never discard a rotated token merely to make a caller fail.
- Write deterministic tests with latches/fakes for races. Avoid timing-only sleeps.
- Test both the expected caller result and the final persisted state.

## 9. Test and release evidence

Minimum evidence depends on the change, but must include relevant:
- Unit tests for success, malformed input, error responses, cancellation, concurrency, and regression cases.
- Backup/restore privacy tests for sensitive data.
- API-27/ARMv7 static compatibility review for platform/build changes.
- Release build, ABI/ELF inspection, package identity, signing-certificate verification, and artifact inspection before calling a build release-ready.
- A clear distinction between checks not run, checks passed, checks failed, and checks blocked.

Do not run a full Release CI merely to verify a documentation-only change.

## 10. Failure response protocol

When a build/test fails:
1. Inspect the workflow run and locate the earliest failed required step.
2. Retrieve the complete relevant job log and identify the exact Gradle task/test/assertion or tool error.
3. Read the failing test and production code, including adjacent callers and lifecycle behavior.
4. Decide whether the failure is a source defect, stale test, environment/toolchain issue, flaky test, dependency/network failure, or CI configuration regression. Do not label it flaky without evidence.
5. Implement the smallest complete correction and add/adjust a regression test that captures the root cause.
6. Review all changed files and relevant related code before the next CI run.
7. Update the failure ledger and report what is verified versus still pending.

## 11. Session start / resume checklist

At the start of every RikkaHub session:
- Read this skill and the relevant project audit/reference documents.
- Inspect repository, active branch, HEAD SHA, recent commits, working tree/PR state, and current CI status.
- Retrieve the latest failure ledger entry and unresolved release gates.
- Confirm the owner’s current request and do not redo already verified work.
- Check current official documentation if external requirements are involved.
- State the next concrete action and execute it when authorized; do not substitute repeated planning for implementation.

## 12. Communication and checkpoint format

Communicate in Indonesian, concise and practical. Do not ask the owner to repeat facts already established. Do not make the owner perform heavy local work. For checkpoints, include:
- active branch and exact HEAD/commit;
- what changed and why;
- files touched;
- tests/checks actually performed and their results;
- CI run link and artifact details, if any;
- remaining blockers and the single next action.

A checkpoint pasted into a new session means resume from that exact state: inspect current remote state, verify the checkpoint, and continue without restarting completed work.

## 13. Current known project baseline (verify live before relying)

- Routine CI policy: Release-only APK build; Debug APK is diagnostic-only.
- Previously verified Release-only baseline: Build #156, run `45776766405`, commit `9af0b3b42d3213f577a5422abf6fb944081408dd`. This is historical evidence, not proof that the current branch is green.
- Recent SIWC/logout work has had a failing race regression test in Build #161. Do not mark it fixed until the exact failing test passes in a later verified run.
- SIWC migration is not complete merely because OAuth groundwork, metadata fields, backup sanitization, or individual tests exist. Re-read the audit and current source.
- Preserve the verified permanent release signing identity; never expose or regenerate it casually.

---
**Operating rule:** No speculative patch, no success claim without evidence, no unnecessary CI run, and no local build burden on the owner's phone.
