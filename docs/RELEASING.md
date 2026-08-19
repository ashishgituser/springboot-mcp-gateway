# Releasing to Maven Central

This is a one-time setup checklist plus the steps for each release. The `release` Maven profile
and `.github/workflows/release.yml` already do the mechanical work (sign, attach sources/javadoc,
upload to the Central Publisher Portal) — what's below is the account/key setup that has to happen
outside this repo first, by whoever owns the `io.github.ashishgituser` namespace.

## One-time setup

1. **Create a Central Publisher Portal account** at [central.sonatype.com](https://central.sonatype.com) — sign in with **"Sign in with GitHub"**, not email/password, since that's what makes the next step automatic.
2. **Verify the `io.github.ashishgituser` namespace**: Namespaces → Add Namespace → `io.github.ashishgituser`. Because you're authenticated as that GitHub account, Central verifies ownership automatically, no DNS or extra proof needed. If it doesn't auto-verify, the fallback is a short-lived public gist under `ashishgituser` containing the verification code Central gives you.
3. **Generate a portal user token** (Account → Generate User Token). This gives you a username/password pair — not your login credentials — used for deployment.
4. **Generate a GPG key pair** if you don't already have one:
   ```bash
   gpg --full-generate-key   # RSA, 4096 bits
   gpg --list-secret-keys --keyid-format=long   # note the key ID
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```
5. **Add repository secrets** (Settings → Secrets and variables → Actions) on this repo:
   - `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD` — from step 3.
   - `GPG_PRIVATE_KEY` — `gpg --export-secret-keys --armor <KEY_ID>`, the full ASCII-armored block.
   - `GPG_PASSPHRASE` — the key's passphrase.

## Cutting a release

1. Bump versions and tag as usual (`mvn versions:set -DnewVersion=X.Y.Z`, commit, `git tag vX.Y.Z`, push commit and tag).
2. Run the **Release** workflow from the Actions tab (`workflow_dispatch`) against that tag, or locally:
   ```bash
   mvn -B -ntp -Prelease deploy
   ```
3. The upload lands in the Central Portal as a *validated* deployment — nothing is published automatically (`autoPublish` is `false` in the `release` profile on purpose). Sign in to the portal, review it, and click Publish.
4. Once it's live, update the README's [Getting it](../README.md#getting-it) section to show the Maven Central coordinates instead of (or alongside) JitPack, and check off "Maven Central release" in the roadmap.
