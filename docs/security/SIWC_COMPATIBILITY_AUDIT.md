# Sign in with ChatGPT compatibility audit

Status: **BLOCKED — current ChatGPT account OAuth is a legacy Codex-parameter reuse, not a verified SIWC implementation.**

Audit basis: repository source at the audit commit and the public OpenAI Sign in with ChatGPT documentation reviewed on 2026-11-25.

## Current implementation

- `ChatGPTAuth.kt` hard-codes the Codex CLI client ID `app_EMoamEEZ73f0CkXaXp7hrann`.
- Authorization and token URLs use `/oauth/authorize` and `/oauth/token`, with Codex-specific query parameters.
- The shared OAuth callback model now captures `client_id`, `scope`, and `iss` in addition to the standard callback fields. The ChatGPT account flow does not yet consume or validate these values.
- The account manager exchanges the code using the hard-coded client ID. The generic OAuth client now supports OIDC `nonce` authorization parameters and generates a cryptographically random nonce, but the ChatGPT flow does not yet bind or validate one.
- JWT payloads are decoded locally for display claims; signature, issuer, audience, expiry, nonce, and subject are not cryptographically validated.
- Stored credentials do not preserve the SIWC registration/client mapping or stable host identifier.

## Official SIWC requirements that are not met

The current open-source SIWC registration flow documents:

1. First registration with `client_id=dynamic_agent_client`, the actual app name in `agent_name_hint`, and a persistent per-installation `ext_agent_host_id`.
2. The authorization callback returns an issued client ID for the new registration. That issued ID, not `dynamic_agent_client`, must be persisted and reused for later sign-ins to the same account registration.
3. The documented endpoints are `https://auth.openai.com/api/accounts/authorize` and `https://auth.openai.com/api/accounts/oauth/token`. The code exchange uses the issued client ID and the same callback URI/resource. The current app instead uses the legacy `/oauth/authorize` and `/oauth/token` endpoints.
4. Validate the ID token signature against OpenAI JWKS and verify issuer, audience, expiry, nonce, and subject before storing or activating credentials.
5. Check the granted scopes before enabling ChatGPT plan usage. A valid identity token alone does not grant plan-use permission.
6. Account registrations and credentials must remain separate; sign-out should attempt renewable-session revocation before clearing tokens.

References:
- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions

## OAuth foundation and adjacent lifecycle hardening

The shared OAuth module now has optional OIDC nonce generation and authorization URL support. The loopback callback server captures the optional SIWC client_id, scope, and RFC 9207 iss response parameters, with unit coverage for callback parsing and URL construction.

The ChatGPT account flow does **not** yet consume those fields, use the nonce, perform dynamic registration, or validate an ID token. These primitives are groundwork only and do not change the SIWC compatibility status.

The adjacent account-login lifecycle now uses per-provider attempt generations. A cancelled or superseded browser login cannot persist its late token response; persistence also checks that the provider still exists and retains the same account-provider type. This is lifecycle hardening, not SIWC protocol completion.

## Decision and release gate

- Do not claim the existing implementation is officially approved, SIWC-compliant, or authorized for third-party reuse. No permission basis for reusing the Codex CLI client ID has been established by this audit.
- Do not partially swap the client ID or endpoint: doing so would break the current flow without implementing registration, callback capture, nonce/host identity, validation, scope handling, and persistence.
- Preserve existing stored credentials and the currently working login path until a complete replacement is implemented and tested.
- Treat this as a **P0 compatibility/security release gate**. Before claiming SIWC support, implement the complete documented flow and its tests, or remove/disable the unsupported ChatGPT account-login claim and direct users to a supported API-key path.

## Remaining implementation work

- Wire the newly supported callback `client_id`, `scope`, and `iss` fields into ChatGPT login state, and validate the returned values.
- Generate and persist a stable `ext_agent_host_id`; wire the generic nonce generator into ChatGPT authorization and validate the returned ID-token nonce. Generic nonce generation and URL support now exist but are not yet used by ChatGPT login.
- Implement first-time dynamic registration and returning-account client ID reuse with separate account registrations.
- Exchange against the documented SIWC token endpoint using the issued client ID.
- Add JOSE/JWKS signature verification and strict OIDC claim validation.
- Persist verified subject, client registration, host ID, and granted scopes without mixing account registrations.
- Implement revocation-aware sign-out and safe credential replacement.
- Add deterministic tests for callback tampering, wrong issuer/audience/nonce, expired tokens, bad signatures, missing scopes, account switching, refresh rotation, and logout.
