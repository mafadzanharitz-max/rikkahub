# Sign in with ChatGPT compatibility audit

Status: **BLOCKED — current ChatGPT account OAuth is a legacy Codex-parameter reuse, not a verified SIWC implementation.**

Audit basis: repository source at commit `aa9bac9779360dced1edc03a0a7f6c118492f899` and the public OpenAI Sign in with ChatGPT documentation and Terms reviewed on 2026-11-26.

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


## Additional P0 finding: the inference transport is not the SIWC transport

A second, independent blocker was confirmed against the official SIWC Models and inference guide reviewed on 2026-11-26:

- `ai/.../providers/chatgpt/ChatGPTProvider.kt` currently sends model discovery to `https://chatgpt.com/backend-api/codex/models`.
- It sends inference to `https://chatgpt.com/backend-api/codex/responses`.
- It queries subscription usage through `https://chatgpt.com/backend-api/wham/usage`.
- It adds Codex-specific `ChatGPT-Account-ID`, `originator: codex_cli_rs`, and session headers, and uses Codex-specific Responses capabilities.
- The official SIWC plan-usage guide instead requires model discovery from `https://api.openai.com/v1/models` and inference through `POST https://api.openai.com/v1/responses` with the SIWC access token as Bearer authorization. It explicitly says not to use ChatGPT `backend-api` endpoints. The request must set `store: false` and `stream: true`.
- The current `/backend-api/wham/usage` quota endpoint is not part of the documented SIWC interface. Do not retain it as a fallback. Until a supported usage source is documented, account quota should be unavailable or link users to ChatGPT Settings → Usage.

This means a correct SIWC token flow alone would still leave the ChatGPT provider using undocumented Codex backend APIs. SIWC migration must include the provider transport, model catalogue parser, quota behavior, and tests. The generic `ResponseAPI` already sets `store: false` and can stream; its default capabilities should be evaluated for the public Responses API rather than reusing `CODEX_CAPABILITIES`.

Official reference:
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference

## SIWC Terms and user-control requirements

The OpenAI SIWC Terms effective 2026-11-04 add explicit obligations that must be included in the release gate:

- Use the actual app name consistently; do not impersonate OpenAI, another app, or another open-source project.
- Authentication tokens must be obtained only through the supported flow and persisted locally under the user's control. Do not move persistent tokens to remote/managed storage, analytics, logs, support transcripts, or source control.
- ChatGPT plan requests must originate from the user's local runtime (or a remote runtime only that same user controls), be for that authenticated user, and arise from their activity or background automation they expressly authorized.
- Do not expose a general-purpose API for unrelated tools or requests. Do not charge users or require a paid app tier for using their plan through SIWC.
- Do not enable or encourage creating multiple accounts, splitting usage, rotating accounts, pooling/transferring/reselling/sharing tokens, or using one user's subscription for another user's requests.

The official open-source SIWC documentation supports keeping separate registration records where users have legitimate separate account/workspace registrations. That must not be presented as a way to evade plan limits or encourage account rotation. Any account-selection UI must preserve user control and the original account/client binding.

References:
- https://openai.com/policies/sign-in-with-chatgpt-terms/
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions

## Updated migration sequence

The SIWC work is larger than an OAuth-only change. Required sequence:

1. Replace the legacy Codex OAuth authorization with the documented SIWC dynamic registration and reauthorization flow. Keep first-time registration separate from a saved issued client ID.
2. Persist one stable host ID per local app host, plus each registration's issued client ID, validated subject/issuer, ID token hint, tokens, scopes, and expiry. Keep each registration's record independent and protected by local Android Keystore-backed encryption.
3. Validate ID-token RS256 signature using the fixed OpenAI JWKS endpoint with key rotation handling; validate issuer, audience, expiry, nonce, subject, and any optional claims used.
4. Require the token response's granted `chatgpt.tokens.use.direct` scope before enabling plan inference. A callback scope is not authoritative for this decision.
5. Replace Codex `backend-api` model discovery and inference with the documented public `api.openai.com/v1/models` and `/v1/responses` transport. Keep `store: false`, streaming, and user-local execution. Remove undocumented quota calls and Codex-only headers/assumptions unless separately documented for SIWC.
6. Implement form-encoded refresh with the issued client ID and resource, serialize refresh-token rotation, and implement revocation via the discovery revocation endpoint. Clear local tokens safely and report when remote revocation could not be confirmed.
7. Audit settings export, backup, restore, synchronization, logging, diagnostics, and background execution so SIWC tokens remain local, user-controlled, and never leak into remote persistence or unrelated activity.
8. Add deterministic tests for registration callback handling, account binding, JWKS signature/key rotation, claim validation, granted scopes, refresh rotation, revocation failures, model discovery, public Responses request shape, and prohibited legacy backend endpoint usage.

Do not change the current login or provider transport in isolation. Preserve the existing user data while implementing and testing the complete replacement. The SIWC release gate remains **P0 / BLOCKED** until both the authentication protocol and the inference transport satisfy the official documentation and Terms.

## Portable backup privacy mitigation

The current backup path serializes the in-memory `Settings` object after DataStore decryption. The resulting `settings.json` therefore contains ChatGPT OAuth credential fields inside the password-encrypted archive. Since the same archive can be sent to remote WebDAV or S3 storage, this is not an acceptable persistence path for SIWC authentication tokens, even though the archive itself is encrypted.

A code mitigation is being added at the backup serialization boundary: retain the ChatGPT provider configuration but remove its complete OAuth credential before serialization. Restoring such a backup leaves the ChatGPT provider present but signed out; the user must complete sign-in again on the restored device. This also prevents the retained ID token hint, access token, and refresh token from crossing the backup boundary. Existing backups already created are not retroactively changed and should be treated as sensitive.

The per-host `ext_agent_host_id` must also remain host-local and must not be copied into portable backups when the SIWC host setting is introduced.

## SIWC preview limitations that affect the existing feature surface

The official preview-limitations guide adds a compatibility matrix that must be applied to the provider migration:

- Every HTTP inference request must set `store: false` and `stream: true`, and send the complete required context in `input` on each request. The current `ResponseAPI` already streams and sends `store: false`; it also sends the full local history rather than relying on `previous_response_id`.
- Omit unsupported request fields including `max_output_tokens`, `temperature`, `top_p`, `background`, `conversation`, `metadata`, `moderation`, `multi_agent`, `prompt`, `prompt_cache_retention`, `safety_identifier`, `top_logprobs`, `truncation`, and `user`.
- The current shared `ResponseAPI` may add `max_output_tokens`, `temperature`, and `top_p` by default. A SIWC-specific capability profile must disable those fields. It must also remove Codex-only encrypted reasoning payload assumptions unless explicitly supported by the SIWC preview contract.
- Supported tool behavior is narrower than the current provider's built-in tool surface. Function/custom tools are supported subject to the documented input contract; web search is subject to model and account/workspace policy.
- Image generation, file search, Code Interpreter, native computer use, hosted MCP/connectors, and Responses `tool_search` are not supported by this SIWC preview. The current ChatGPT provider can emit an image-generation tool, so that capability must be disabled for SIWC rather than silently sent.
- Do not rely on Codex-only request fields, undocumented session headers, or previous response IDs. Keep all conversation context needed for a turn in the submitted input.

Official reference:
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
