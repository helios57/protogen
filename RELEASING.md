# Releasing to Maven Central

Releasing is: **push a tag**. The workflow builds, tests, signs, uploads and publishes; nothing else is
needed.

**Status:** published. `io.github.helios57.protogen` is live on Central, the four secrets are in place and
`autoPublish` is on, so a tag goes all the way through without a confirmation click.

---

## Releasing

```bash
# 1. drop the -SNAPSHOT
mvn -B versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
git commit -am "Release X.Y.Z"

# 2. tag it - this is what triggers the workflow
git tag -a vX.Y.Z -m "protogen X.Y.Z"
git push origin main vX.Y.Z

# 3. back to snapshots
mvn -B versions:set -DnewVersion=<next>-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Back to snapshot"
git push
```

The workflow refuses to publish if the pom version does not match the tag, or if it is a `SNAPSHOT`.

Then write the release notes: add the section to [CHANGELOG.md](CHANGELOG.md) before tagging, and create
the GitHub release from the tag afterwards.

### Trying it without publishing

Run the workflow manually with **dryRun** ticked
(<https://github.com/helios57/protogen/actions/workflows/release.yml> → Run workflow). It builds, signs
and uploads the artifacts as a GitHub artifact without touching Central — worth doing after any change to
the signing setup, because **a Central deployment cannot be deleted once released**.

---

## The one-time setup, for reference

All of this is done. It is kept because it is the part that cannot be scripted, and because a lost
machine or a rotated key means doing it again.

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

The key in use is `rsa4096/4A438D5FE3746224`, `helios57 <helios157@gmail.com>`, valid to 2046. Its public
half is on `keyserver.ubuntu.com` and its private half is in the `GPG_PRIVATE_KEY` secret. To replace it,
both halves have to be published and exported again:

```bash
# 1. publish the public half; Central looks the key up here, and keyservers do not forget
gpg --keyserver keyserver.ubuntu.com --send-keys 4A438D5FE3746224

# 2. export the private half for CI (treat this output as a password)
gpg --armor --export-secret-keys 4A438D5FE3746224
```

> **Step 1 is not optional and is easy to skip.** The first release attempt uploaded and signed everything
> correctly, then Central rejected the whole deployment with *"Could not find a public key by the key
> fingerprint"* for every artifact. The key had never been sent to a keyserver. Signing locally proves
> nothing about this — Central looks the key up on its own.

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

## What gets published

| Artifact | Published | Why |
|---|---|---|
| `io.github.helios57.protogen:protogen-maven-plugin` | yes | the thing people actually use |
| `io.github.helios57.protogen:protogen-compiler` | yes | the plugin depends on it, so it has to resolve |
| `io.github.helios57.protogen:protogen-parent` | yes (pom) | the two above name it as their parent |
| `protogen-it`, `protogen-interop`, `protogen-benchmark` | **no** | test and benchmark harnesses |

The deploy step names its modules explicitly:

```bash
mvn -Prelease deploy -pl .,protogen-compiler,protogen-maven-plugin
```

`maven.deploy.skip` on the harness modules is *not* sufficient by itself — the central-publishing plugin
gathers artifacts from every module in the reactor regardless, which is how the first release attempt ended
up trying to publish `protogen-benchmark`. Limiting the reactor is what actually excludes them.

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

## Consuming it

```xml
<plugin>
    <groupId>io.github.helios57.protogen</groupId>
    <artifactId>protogen-maven-plugin</artifactId>
    <version>0.2.0</version>
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
