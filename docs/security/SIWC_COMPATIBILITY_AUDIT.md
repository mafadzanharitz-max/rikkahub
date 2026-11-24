# Sign in with ChatGPT compatibility audit

Status: **BLOCKED — current ChatGPT account OAuth is a legacy Codex-parameter reuse, not a verified SIWC implementation.**

Audit basis: repository source at the audit commit and the public OpenAI Sign in with ChatGPT documentation reviewed on 2026-11-25.

## Current implementation

- `ChatGPTAuth.kt` hard-codes the Codex CLI client ID `app_EMoamEEZ73f0CkXaXp7hrann`.
- Authorization and token URLs use `/oauth/authorize` and `/oauth/token`, with Codex-specific query parameters.
- The callback model captures only `code`, `state`, `error`, and `error_description`; it cannot retain an issued client ID, granted scopes, or issuer response parameter.
- The account manager exchanges the code using the hard-coded client ID.
- JWT payloads are decoded locally for display claims; signature, issuer, audience, expiry, nonce, and subject are not cryptographically validated.
- Stored credentials do not preserve the SIWC registration/client mapping or stable host identifier.

## Official SIWC requirements that are not met

The current open-source SIWC registration flow documents:

1. First registration with `client_id=dynamic_agent_client`, the actual app name in `agent_name_hint`, and a persistent per-installation `ext_agent_host_id`.
2. The authorization callback returns an issued client ID for the new registration. That issued ID, not `dynamic_agent_client`, must be persisted and reused for later sign-ins to the same account registration.
3. The code exchange uses the issued client ID and the documented SIWC token endpoint.
4. Validate the ID token signature against OpenAI JWKS and verify issuer, audience, expiry, nonce, and subject before storing or activating credentials.
5. Check the granted scopes before enabling ChatGPT plan usage. A valid identity token alone does not grant plan-use permission.
6. Account registrations and credentials must remain separate; sign-out should attempt renewable-session revocation before clearing tokens.

References:
- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions

## Decision and release gate

- Do not claim the existing implementation is officially approved, SIWC-compliant, or authorized for third-party reuse. No permission basis for reusing the Codex CLI client ID has been established by this audit.
- Do not partially swap the client ID or endpoint: doing so would break the current flow without implementing registration, callback capture, nonce/host identity, validation, scope handling, and persistence.
- Preserve existing stored credentials and the currently working login path until a complete replacement is implemented and tested.
- Treat this as a **P0 compatibility/security release gate**. Before claiming SIWC support, implement the complete documented flow and its tests, or remove/disable the unsupported ChatGPT account-login claim and direct users to a supported API-key path.

## Remaining implementation work

- Extend callback parsing and state to capture and validate `client_id`, `scope`, and `iss`.
- Generate and persist a stable `ext_agent_host_id`; generate a fresh OIDC nonce per authorization attempt.
- Implement first-time dynamic registration and returning-account client ID reuse with separate account registrations.
- Exchange against the documented SIWC token endpoint using the issued client ID.
- Add JOSE/JWKS signature verification and strict OIDC claim validation.
- Persist verified subject, client registration, host ID, and granted scopes without mixing account registrations.
- Implement revocation-aware sign-out and safe credential replacement.
- Add deterministic tests for callback tampering, wrong issuer/audience/nonce, expired tokens, bad signatures, missing scopes, account switching, refresh rotation, and logout.
