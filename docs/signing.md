# Debug signing — one stable key for every CI build

## Why this exists (issues #8 / #14)

Each GitHub Actions run used to sign the debug APK with a fresh
auto-generated debug key: a new `ubuntu-latest` runner has no
`~/.android/debug.keystore`, so AGP creates one on the spot. Every CI
build therefore had a different signing identity, and `adb install -r`
failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on each new build.

The `9fb16b3` retest is blocked by exactly this: the bot will not wipe
the tablet, because wiping destroys the saved ContextForge bearer
needed for the live FR-11/FR-13 test.

The fix: CI restores one stable debug keystore from the
`DEBUG_KEYSTORE_BASE64` repo secret before building (see
`.github/workflows/build-apk.yml`, "Restore stable debug keystore").
AGP signs debug builds with `~/.android/debug.keystore` when it
already exists, so no `build.gradle.kts` change was needed.

## One-time setup (do once, then never again)

Run on any machine with a JDK (Mac Mini is fine). The keystore this
creates is a **debug** key only — it must never sign a release build.
The release keystore lives on the Mac Mini and is untouched by this.

```sh
# 1. Generate the shared debug keystore (standard Android debug credentials).
keytool -genkeypair -keystore jarvis-tablet-debug.keystore \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"

# 2. Base64 it (macOS: base64 -i; Linux: base64 -w0) — keep this file
#    private; it is the only copy of the private key until it is a secret.
base64 -i jarvis-tablet-debug.keystore > jarvis-tablet-debug.keystore.b64

# 3. Add it as a repo secret (needs repo admin on chatwithllm/jarvis-tablet):
#    gh CLI:
gh secret set DEBUG_KEYSTORE_BASE64 --repo chatwithllm/jarvis-tablet \
  < jarvis-tablet-debug.keystore.b64
#    …or GitHub web UI: repo → Settings → Secrets and variables →
#    Actions → New repository secret, name DEBUG_KEYSTORE_BASE64,
#    value = the contents of jarvis-tablet-debug.keystore.b64.

# 4. Delete the local copies once the secret is set (the secret is the
#    only copy from then on; anyone who needs it reads it from the repo
#    secrets, never from chat).
rm jarvis-tablet-debug.keystore jarvis-tablet-debug.keystore.b64
```

Never paste the base64 value into chat, a GitHub issue, or a commit.
The workflow fails hard if the secret is missing, so a build can never
silently go back to an ephemeral key.

## The one final wipe

The tablet currently runs an APK signed by CI run #7's ephemeral key,
which no longer exists. The **first** APK signed with the stable key
will still fail `adb install -r` and needs one uninstall wipe — after
that, every later CI build installs in place.

That wipe destroys the saved ContextForge bearer, so before it: make
sure the tablet-scoped bearer IT Admin provided is saved somewhere
safe (it was entered in Settings on the tablet; re-entering it after
the wipe is the only way back to the live test). After the wipe +
reinstall + bearer re-entry, FR-11/FR-13 can finally be retested on
the stable-key build, and no further wipe should ever be needed for
signing reasons.

## Verifying the fix

1. Push a commit after the secret is set; CI must go green (the
   keystore step fails the run if the secret is missing or invalid).
2. Note the signing-cert SHA-256 of that run's APK:
   `apksigner verify --print-certs app-debug.apk`
3. Push a second commit (any trivial change); the cert SHA-256 must be
   identical. If it differs, the restore step is not running before
   the Gradle build.
4. On the tablet, the second build must `adb install -r` cleanly over
   the first — that is the real-world proof the cycle is broken.
