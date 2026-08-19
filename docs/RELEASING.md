# Releasing to Maven Central

This is a one-time setup checklist plus the steps for each release. The `release` Maven profile
and `.github/workflows/release.yml` already do the mechanical work (sign, attach sources/javadoc,
upload to the Central Publisher Portal) — what's below is the account/key setup that has to happen
outside this repo first, by whoever owns the `io.github.ashishgituser` namespace.

## One-time setup

1. **Create a Central Publisher Portal account** at [central.sonatype.com](https://central.sonatype.com) and verify the `io.github.ashishgituser` namespace. For a `io.github.*` groupId this is done by creating a public GitHub gist (under the `ashishgituser` account) containing the verification code the portal gives you — no DNS access needed.
2. **Generate a portal user token** (Account → Generate User Token). This gives you a username/password pair — not your login credentials — used for deployment.
3. **Generate a GPG key pair** if you don't already have one (`gpg --gen-key`), and publish the public key to a keyserver so Central can verify signatures:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```
4. **Add repository secrets** (Settings → Secrets and variables → Actions) on this repo:
   - `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD` — from step 2.
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
