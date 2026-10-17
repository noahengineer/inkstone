# P0 — measure Microsoft Graph before building on it

`graph_spike.py` runs the eight measurements (M-M1 … M-M8) that `docs/CLOUD-PROVIDERS.md` §9 says must
exist before any Kotlin depends on Graph's behaviour. **It has been compile-checked and never run** —
there was no account to run it against. Read its raw statuses, not only its verdicts.

## What you need to do (about ten minutes)

1. **Use a throwaway personal Microsoft account** (a free outlook.com address is fine). The script
   creates a folder `Inkstone-spike-<time>` in its OneDrive and deletes it (to the recycle bin) at the
   end. It does not touch anything else.
2. **Register the app** — <https://entra.microsoft.com> → *App registrations* → *New registration*:
   - Name: `Inkstone` (the real name; this registration is the one the shipped app will use).
   - Supported account types: **Accounts in any organizational directory and personal Microsoft
     accounts**.
   - Redirect URI: platform **Mobile and desktop applications**, value `http://localhost`.
   - Then *API permissions → Microsoft Graph → Delegated*: `Files.ReadWrite`, `offline_access`,
     `User.Read`. (No admin consent is needed for these.) Do **not** create a client secret.
   - Copy the **Application (client) ID**.
   - Registering a personal-account app may require an Azure account on the registering identity; if
     the portal asks for one, that is the free tier and still needs no payment for this use.
3. **Run it** from the repo root and sign in when the browser opens. You type your password into
   Microsoft's page only; the script never sees it.

   ```
   py tools/cloud/graph_spike.py --client-id <the id from step 2>
   ```

   Add `--approot` to repeat it with `Files.ReadWrite.AppFolder` (also answers whether `delta` works
   there; add that permission to the registration first).
4. Send me `graph-spike-results.json`. It contains no token. The key line is
   `M-M1.VERDICT_rule1_holds_via_upload_session`: **if that is `false`, OneDrive is a blocker for
   rule 1 too, and I stop and report instead of building.**

If Python 3.14 reports `certificate verify failed: Missing Authority Key Identifier`, that is the
strict-TLS behaviour in `tools/web/tlsdiag.py`; the script already uses that helper's relaxed-for-that-
one-failure context, and the helper will say if a device on your network is terminating TLS.
