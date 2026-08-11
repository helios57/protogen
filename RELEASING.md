# Releasing to Maven Central

Everything in the build is ready: `mvn -Prelease verify` already produces the signed jars, sources and
javadoc that Central validates. What is left is an account registration and a set of credentials, which
cannot be scripted because they are tied to your identity.

Once the four secrets exist, releasing is: **push a tag**.

**Status:** not yet published. Nothing about protogen depends on this — the plugin works fine built from
source — but Central is what lets other people use it without cloning.

---

## One-time setup

### 1. A Central Portal account, and the `io.github.helios57` namespace

Sign in at **<https://central.sonatype.com>** *with GitHub*. Signing in with the GitHub account
`helios57` gets the namespace `io.github.helios57` verified automatically — that is the whole reason this
project uses the groupId `io.github.helios57.protogen`. No DNS record, no support ticket.

Check it appears under **Namespaces** as verified before going further.

### 2. A publishing token

In the portal: **your name → View Account → Generate User Token**. It gives you a username/password pair
(not your login). Keep both.

### 3. A GPG key

Central requires every artifact to be signed, and the public key to be findable on a public keyserver.

**A suitable key already exists on the development machine** — `rsa4096/4A438D5FE3746224`,
`helios57 <helios157@gmail.com>`, valid to 2046 — and `mvn -Prelease verify` signs every artifact with it
today. Two things still have to happen, both of which publish or export something and so are left to you:

```bash
# 1. publish the public half; Central looks the key up here, and keyservers do not forget
gpg --keyserver keyserver.ubuntu.com --send-keys 4A438D5FE3746224

# 2. export the private half for CI (treat this output as a password)
gpg --armor --export-secret-keys 4A438D5FE3746224
```

If you would rather use a different key, generate one with
`gpg --quick-generate-key "helios57 <helios157@gmail.com>" rsa4096 sign 2y` and substitute its id.

### 4. Four repository secrets

**Settings → Secrets and variables → Actions → New repository secret** on
<https://github.com/helios57/protogen/settings/secrets/actions>:

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME` | the token username from step 2 |
| `CENTRAL_PASSWORD` | the token password from step 2 |
| `GPG_PRIVATE_KEY` | the entire `gpg --armor --export-secret-keys` output, including the BEGIN/END lines |
| `GPG_PASSPHRASE` | the passphrase for that key — **optional**, leave it out if the key has none |

The release workflow checks the first three up front and fails with a clear message if any is missing,
rather than getting halfway through a publish. The passphrase is not required, because a key without one
is a perfectly valid setup.

> A signing key with no passphrase means the exported secret is, on its own, enough to sign as you. That
> is common practice for CI and nothing here depends on changing it — but a passphrase would make the
> secret useless without a second value, so it is worth knowing you have made that trade.

---

## Releasing

```bash
# 1. drop the -SNAPSHOT
mvn -B versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
git commit -am "Release 0.1.0"

# 2. tag it - this is what triggers the workflow
git tag -a v0.1.0 -m "protogen 0.1.0"
git push origin main v0.1.0

# 3. back to snapshots
mvn -B versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Back to snapshot"
git push
```

The workflow refuses to publish if the pom version does not match the tag, or if it is a `SNAPSHOT`.

### Before the first real release

Run the workflow manually with **dryRun** ticked
(<https://github.com/helios57/protogen/actions/workflows/release.yml> → Run workflow). It builds, signs
and uploads the artifacts as a GitHub artifact without touching Central, so you can confirm the signing
key works before anything is published — Central deployments cannot be deleted once released.

### The first release needs one manual click

`autoPublish` is deliberately `false` in the `release` profile. The workflow uploads and validates; you
then press **Publish** in the portal's *Deployments* view. Once a release has gone through cleanly, flip
`<autoPublish>true</autoPublish>` in the parent pom and later releases are fully hands-off.

---

## What gets published

| Artifact | Published |
|---|---|
| `io.github.helios57.protogen:protogen-parent` | yes (pom) |
| `io.github.helios57.protogen:protogen-compiler` | yes |
| `io.github.helios57.protogen:protogen-maven-plugin` | yes |
| `protogen-it`, `protogen-interop`, `protogen-benchmark` | no — `maven.deploy.skip`, they are test and benchmark harnesses |

Each published artifact carries a sources jar, a javadoc jar and a `.asc` signature, which is what
Central validates.

## Releasing from a laptop instead

```bash
# ~/.m2/settings.xml
# <servers><server><id>central</id>
#   <username>TOKEN_USERNAME</username><password>TOKEN_PASSWORD</password>
# </server></servers>

mvn -Prelease deploy
```

## Consuming it, once released

```xml
<plugin>
    <groupId>io.github.helios57.protogen</groupId>
    <artifactId>protogen-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution><goals><goal>generate</goal></goals></execution>
    </executions>
</plugin>
```

Still no dependency added to the consuming project — that remains the point.

## Sources

- [Central Publisher Portal — publishing with Maven](https://central.sonatype.org/publish/publish-portal-maven/)
- [Central Publisher Portal guide](https://central.sonatype.org/publish/publish-portal-guide/)
- [Generating a portal token](https://central.sonatype.org/publish/generate-portal-token/)
