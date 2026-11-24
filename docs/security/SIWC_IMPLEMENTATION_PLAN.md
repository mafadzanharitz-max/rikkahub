# Sign in with ChatGPT (SIWC) implementation plan

Status: **design only — no SIWC protocol migration is implemented by this document**.

Reviewed against the official open-source SIWC documentation on 2026-11-25:
- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions

## Current repository constraints

- `ChatGPTAuth` still uses a legacy Codex CLI client ID and legacy `/oauth/authorize` and `/oauth/token` endpoints.
- `ProviderSetting.ChatGPT` currently stores one `OAuthCredential`, not multiple independent account registrations.
- `OAuthCredential` does not retain an issued SIWC client ID, verified issuer/subject, granted scope set, or retained ID token.
- The generic OAuth layer now supports nonce generation and authorization parameters; the loopback callback captures `client_id`, `scope`, and `iss`. The ChatGPT flow does not consume or validate them.
- Current JWT payload decoding is not cryptographic ID-token validation. Do not use decoded claims as trusted identity.
- No permission basis for reusing the legacy Codex client ID has been established.

Do not replace the legacy client ID or endpoints in isolation. Preserve existing credentials and login behavior until a complete, independently tested SIWC implementation is ready.

## Target architecture

### 1. Stable host identity

Create one opaque, stable `ext_agent_host_id` per RikkaHub installation and persist it before the first SIWC authorization.

- UUIDv4 URN (`urn:uuid:<uuid>`) is a supported implementation option.
- A JWK-thumbprint URN is the documented preferred option for new implementations.
- Never derive the host ID from email, account ID, device serial, or another identifying secret.
- App reinstall/backup migration policy must be explicit. Do not silently generate a new ID on every login.

### 2. Independent account registrations

Introduce a backward-compatible collection of ChatGPT account profiles. Each profile must keep its own:
- stable local profile ID and user-facing label;
- issued SIWC `client_id`;
- validated issuer and subject;
- email/display metadata from the validated ID token;
- granted scopes;
- access token, refresh token, expiry, and retained ID token;
- registration/creation metadata needed for diagnostics without exposing secrets.

Keep the active profile ID separate from the profile collection. Switching profiles must switch the client ID and token set as one atomic unit. Never combine one profile's client ID with another profile's tokens, even when email addresses match.

Legacy `OAuthCredential` data must remain readable. Treat it as legacy-only data; do not invent a SIWC client mapping or silently promote it to a registered profile.

### 3. First-time registration and returning sign-in

Use the documented open-source flow:

- Authorization endpoint: `https://auth.openai.com/api/accounts/authorize`.
- Token endpoint: `https://auth.openai.com/api/accounts/oauth/token`.
- Resource: `https://api.openai.com/v1`.
- First registration uses `client_id=dynamic_agent_client`, the actual stable app name in `agent_name_hint`, and the persisted `ext_agent_host_id`.
- Capture the issued `client_id` from the loopback callback and use that issued value for token exchange and future sign-ins. Never persist or reuse `dynamic_agent_client` as the profile client ID.
- Returning sign-in uses that profile's issued client ID and the same host ID. Send its retained `id_token_hint` and optional matching `login_hint`; omit `agent_name_hint` on reauthorization.
- Use a loopback callback on `127.0.0.1`, with the documented `/auth/callback` path. Bind the listener before opening the browser. Use the exact same URI, including port, in authorization and token exchange; do not substitute `localhost`.
- Generate fresh state, nonce, and PKCE values for every attempt. Validate state before token exchange.

The documented scope request for plan use includes `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`. A successful identity sign-in without the plan-use scopes is not authorization to use a ChatGPT plan.

### 4. ID-token and permission validation

Before persisting or activating a new profile:

1. Load the OpenID configuration from `https://auth.openai.com/.well-known/openid-configuration`; validate discovered endpoints against the expected issuer and HTTPS policy.
2. Verify the JWT signature against the issuer's JWKS with a maintained JOSE implementation and safe key rotation behavior.
3. Require and validate issuer, audience, expiry, issued-at, subject, and the exact nonce generated for this attempt.
4. Validate callback registration metadata and associate the issued client ID with the verified profile.
5. Parse the granted scope set and require `resource.invoke` and `chatgpt.tokens.use.direct` before exposing ChatGPT plan usage.
6. Treat optional email/profile claims as display metadata only; handle absent or unverified email without weakening identity validation.

No credential or account metadata from an unverified token may be trusted or persisted as verified identity.

### 5. Refresh, account switching, and sign-out

- Refresh under a per-profile lock; re-read the profile before sending a refresh token.
- Persist rotated tokens only if the exact profile/client mapping and expected previous credential still match.
- Preserve the retained ID token when a refresh response omits a replacement.
- Complete and validate a new sign-in before changing the active profile.
- On sign-out, attempt renewable-session revocation using the discovered revocation endpoint with the refresh token, `token_type_hint=refresh_token`, and that profile's issued client ID. Then clear the selected profile's access, refresh, and ID tokens while retaining its client registration mapping and the host ID, as documented.

### 6. Request-path integration

- Keep plan-use credentials restricted to the documented OpenAI resource and supported request path.
- Require the profile's validated plan-use scope before enabling the feature.
- Keep account credentials out of logs, diagnostics, URLs, analytics, and crash reports.
- Preserve existing API-key provider behavior and other account providers.

## Required deterministic test matrix

- New registration sends the required client ID, app name, host ID, scopes, resource, state, nonce, PKCE, and exact callback URI.
- Callback state mismatch, missing issued client ID, unexpected issuer, malformed callback, and duplicate callback are rejected.
- Token exchange uses the callback-issued client ID, not `dynamic_agent_client`.
- Invalid signature, unknown signing key, wrong issuer, wrong audience, expired token, future/invalid timestamps, wrong nonce, and missing subject are rejected.
- Missing `resource.invoke` or `chatgpt.tokens.use.direct` prevents plan-use activation.
- Returning sign-in reuses only the selected profile's issued client ID and matching hints.
- Two profiles with the same email remain separate.
- Profile switching cannot mix credentials; stale login and refresh results cannot overwrite a newer profile or logout.
- Refresh rotation, revoked refresh tokens, sign-out revocation failure, and app restart persistence are covered.
- No timing sleeps; use injected fake discovery, browser, callback, clock, token endpoint, and persistence collaborators.

## Release gate

SIWC remains a P0 compatibility/security gate if RikkaHub advertises ChatGPT plan use. Until the complete flow and tests pass, do not claim SIWC compliance or permission to reuse the Codex CLI client ID. Do not remove the legacy path or enable a partially implemented replacement as a side effect of this design work.
