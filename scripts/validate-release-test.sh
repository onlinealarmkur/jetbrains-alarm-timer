#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
repository_root=$(cd "$script_dir/.." && pwd)
validator="$script_dir/validate-release.sh"
temporary_root=$(mktemp -d)
trap 'rm -rf "$temporary_root"' EXIT

contract_failure() {
  printf 'missing release contract: %s\n' "$*" >&2
  exit 1
}

require_literal() {
  local file=$1
  local expected=$2
  local description=$3

  grep -Fq -- "$expected" "$file" || contract_failure "$description"
}

# Workflow checks operate only on active YAML, never on comments carrying stale contract text.
require_active_literal() {
  local file=$1
  local expected=$2
  local description=$3

  awk -v expected="$expected" '
    !/^[[:space:]]*#/ && index($0, expected) { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$file" || contract_failure "$description"
}

require_job_literal() {
  local workflow=$1
  local job=$2
  local expected=$3
  local description=$4

  awk -v job="  $job:" -v expected="$expected" '
    /^  [[:alnum:]_-]+:$/ { in_job = ($0 == job); next }
    in_job && !/^[[:space:]]*#/ && index($0, expected) { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$workflow" || contract_failure "$description"
}

require_job_order() {
  local workflow=$1
  local job=$2
  local first=$3
  local second=$4
  local description=$5

  awk -v job="  $job:" -v first="$first" -v second="$second" '
    /^  [[:alnum:]_-]+:$/ { in_job = ($0 == job); next }
    in_job && !/^[[:space:]]*#/ && !first_seen && index($0, first) { first_seen = NR }
    in_job && !/^[[:space:]]*#/ && index($0, second) { second_seen = NR }
    END { exit first_seen && second_seen && first_seen < second_seen ? 0 : 1 }
  ' "$workflow" || contract_failure "$description"
}

# The change notes are versioned release source, so this contract is pinned to
# whatever version the tree currently declares instead of to a frozen sentence.
require_current_change_notes() {
  local notes="$repository_root/MARKETPLACE_CHANGE_NOTES.html"
  local listing="$repository_root/README.md"
  local body_file="$temporary_root/current-change-notes-body.txt"
  local plugin_version declared_version line

  [[ -f "$notes" ]] ||
    contract_failure 'MARKETPLACE_CHANGE_NOTES.html must exist as versioned release source'

  plugin_version=$(sed -n 's/^pluginVersion=//p' "$repository_root/gradle.properties")
  [[ -n "$plugin_version" ]] ||
    contract_failure 'gradle.properties must declare pluginVersion'

  declared_version=$(sed -n 's/^[[:space:]]*<!-- version: \(.*\) -->[[:space:]]*$/\1/p' "$notes")
  [[ "$declared_version" == "$plugin_version" ]] ||
    contract_failure "MARKETPLACE_CHANGE_NOTES.html must declare version '$plugin_version', found '$declared_version'"

  require_literal "$listing" \
    "## Release notes for $plugin_version" \
    "README must carry the release-notes section for $plugin_version"

  awk 'NF && !seen { seen = 1; next } seen' "$notes" |
    sed 's/<[^>]*>//g' >"$body_file"
  [[ -s "$body_file" ]] ||
    contract_failure 'MARKETPLACE_CHANGE_NOTES.html must contain a change-notes body'

  while IFS= read -r line; do
    [[ -n "${line//[[:space:]]/}" ]] || continue
    require_literal "$listing" "$line" \
      "README must mirror the $plugin_version change-notes body"
  done <"$body_file"
}

require_lifecycle_copy() {
  local file=$1
  local surface=$2

  require_literal "$file" \
    'Alarms and timers can alert you only while a compatible JetBrains IDE is running.' \
    "$surface must explain the IDE-running limitation"
  require_literal "$file" \
    'Minimizing the IDE does not stop alerts.' \
    "$surface must explain minimized-IDE behavior"
  require_literal "$file" \
    'Closing the IDE or system sleep delays delivery.' \
    "$surface must explain closed-IDE and system-sleep behavior"
  require_literal "$file" \
    'Eligible overdue items are handled after startup or activation.' \
    "$surface must explain overdue recovery"
}

require_immutable_action_pins() {
  local workflow=$1
  local reference

  while IFS= read -r reference; do
    [[ "$reference" == ./* ]] && continue
    [[ "$reference" =~ ^[^@[:space:]]+@[0-9a-f]{40}$ ]] ||
      contract_failure "every external action in ${workflow#"$repository_root/"} must use a full 40-character commit SHA; found '$reference'"
  done < <(
    sed -nE 's/^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*([^[:space:]#]+).*$/\2/p' "$workflow"
  )
}

require_signing_secret_isolation() {
  local workflow=$1

  if awk '
    /^  sign:$/ { in_sign = 1; next }
    /^  [[:alnum:]_-]+:$/ && in_sign { in_sign = 0 }
    /secrets\./ && !in_sign { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$workflow"; then
    contract_failure 'signing secrets must appear only inside the isolated sign job'
  fi
}

# GNU grep exits immediately after a quiet match. With pipefail enabled, a
# producer still writing a sufficiently large workflow can then fail with
# SIGPIPE and turn a successful match into a false contract failure.
active_literal_fixture="$temporary_root/active-literal.yml"
awk 'BEGIN {
  print "# tags:"
  print "tags:"
  for (line = 0; line < 10000; line++) print "filler: value"
}' >"$active_literal_fixture"
require_active_literal \
  "$active_literal_fixture" \
  'tags:' \
  'active workflow matching must be portable under pipefail'

validate_release_contract() {

  require_literal "$repository_root/README.md" \
    'Set one-time alarms and time-based reminders, or run multiple countdown timers inside your JetBrains IDE.' \
    'README Marketplace metadata must contain the canonical short description'
  require_literal "$repository_root/src/main/resources/META-INF/plugin.xml" \
    '<p>Set one-time alarms and time-based reminders, or run multiple countdown timers inside your JetBrains IDE.</p>' \
    'plugin descriptor must open with the canonical short description'

  require_current_change_notes

  require_literal "$repository_root/build.gradle.kts" \
    'layout.projectDirectory.file("MARKETPLACE_CHANGE_NOTES.html")' \
    'Gradle plugin configuration must read the versioned change-notes file'
  require_literal "$repository_root/build.gradle.kts" \
    'changeNotes = marketplaceChangeNotes' \
    'Gradle plugin configuration must package the versioned change notes'
  require_literal "$repository_root/build.gradle.kts" \
    'pluginVerifier("1.410")' \
    'release verification must pin the IntelliJ Plugin Verifier version'
  require_literal "$repository_root/build.gradle.kts" \
    'zipSigner("0.1.43")' \
    'release signing must pin the Marketplace ZIP Signer version'
  require_literal "$repository_root/build.gradle.kts" \
    'create(IntelliJPlatformType.IntellijIdea, "2026.2.1")' \
    'release verification must pin the current stable IntelliJ IDEA version'
  require_literal "$repository_root/build.gradle.kts" \
    'create(IntelliJPlatformType.PyCharm, "2026.2.1")' \
    'release verification must pin the current stable PyCharm version'
  require_literal "$repository_root/build.gradle.kts" \
    'create(IntelliJPlatformType.WebStorm, "2026.2.1")' \
    'release verification must pin the current stable WebStorm version'
  require_literal "$repository_root/src/main/resources/META-INF/plugin.xml" \
    '<idea-plugin url="https://onlinealarmkur.com/en/">' \
    'plugin descriptor must use the public plugin homepage'
  require_literal "$repository_root/README.md" \
    "- Homepage: \`https://onlinealarmkur.com/en/\`" \
    'README Marketplace metadata must use the public plugin homepage'

  require_lifecycle_copy "$repository_root/src/main/resources/META-INF/plugin.xml" 'plugin descriptor'
  require_lifecycle_copy "$repository_root/README.md" 'README'
  require_literal "$repository_root/src/main/resources/messages/AlarmTimerBundle.properties" \
    'panel.ide.running.limit=Alarms and timers can alert you only while a compatible JetBrains IDE is running.' \
    'base UI bundle must explain the IDE-running limitation'

  require_literal "$repository_root/build.gradle.kts" \
    '"MARKETPLACE_CHANGE_NOTES.html",' \
    'release contract task must track Marketplace change notes explicitly'
  require_literal "$repository_root/build.gradle.kts" \
    '"gradle/verification-metadata.xml",' \
    'release contract task must track dependency verification metadata'
  [[ -f "$repository_root/gradle/verification-metadata.xml" ]] ||
    contract_failure 'Gradle dependency verification metadata must be committed'
  require_literal "$repository_root/build.gradle.kts" \
    'tasks.named<SignPluginTask>("signPlugin") {' \
    'signing task must be configured explicitly'
  require_literal "$repository_root/build.gradle.kts" \
    'archiveFile.set(unsignedPluginArchive)' \
    'signing task must consume the verified unsigned ZIP'
  require_literal "$repository_root/build.gradle.kts" \
    'signedArchiveFile.set(signedPluginArchive)' \
    'signing task must write the exact signed ZIP path'
  require_literal "$repository_root/build.gradle.kts" \
    'setDependsOn(emptyList<Any>())' \
    'signing task must not rebuild the plugin'
  require_literal "$repository_root/build.gradle.kts" \
    'tasks.named("verifyPluginSignature") {' \
    'signature verification must declare signing-task ordering'
  require_literal "$repository_root/build.gradle.kts" \
    'dependsOn("signPlugin")' \
    'signature verification must run after signing'
  require_literal "$repository_root/build.gradle.kts" \
    'certificateChainFile = layout.buildDirectory.file("signing/chain.crt")' \
    'signing and verification must use the runner-local certificate file'
  require_literal "$repository_root/build.gradle.kts" \
    'privateKeyFile = layout.buildDirectory.file("signing/private.pem")' \
    'signing and verification must use the runner-local private-key file'
  require_literal "$repository_root/build.gradle.kts" \
    'val verifySignPluginIsolation = tasks.register("verifySignPluginIsolation") {' \
    'release build must verify signing-task isolation'
  require_literal "$repository_root/build.gradle.kts" \
    'dependsOn(verifySignPluginIsolation)' \
    'release gate must verify signing-task isolation'
  require_active_literal "$repository_root/.github/workflows/release.yml" \
    'tags:' \
    'release workflow must be triggered by a version tag'
  require_active_literal "$repository_root/.github/workflows/release.yml" \
    '"[0-9]+.[0-9]+.[0-9]+"' \
    'release workflow must accept stable numeric version tags'
  require_job_literal "$repository_root/.github/workflows/release.yml" build \
    "if: github.repository_owner == 'onlinealarmkur' && github.actor == 'ozdemirburak'" \
    'release workflow must restrict tag authorization to the designated maintainer account'
  require_job_literal "$repository_root/.github/workflows/release.yml" build \
    "scripts/validate-release.sh tagged-identity \"\$RELEASE_VERSION\"" \
    'release workflow must validate the checked-out release tag'
  require_job_literal "$repository_root/.github/workflows/release.yml" build \
    './gradlew --dependency-verification=strict clean verifyReleaseCandidate' \
    'release workflow must run the complete release-candidate gate'
  require_job_literal "$repository_root/.github/workflows/release.yml" build \
    'Reclaim disk space for IDE verification' \
    'release workflow must reclaim runner space for the complete IDE verification matrix'
  require_job_literal "$repository_root/.github/workflows/release.yml" build \
    '/usr/local/lib/android' \
    'release workflow disk cleanup must remove the unused Android SDK'
  require_job_literal "$repository_root/.github/workflows/release.yml" build \
    '/opt/hostedtoolcache/CodeQL' \
    'release workflow disk cleanup must remove the unused CodeQL bundle'
  require_job_order "$repository_root/.github/workflows/release.yml" build \
    'Reclaim disk space for IDE verification' \
    'Verify release candidate' \
    'release workflow must reclaim runner space before IDE verification'
  require_job_literal "$repository_root/.github/workflows/release.yml" build \
    "scripts/validate-release.sh archive \"\$RELEASE_VERSION\" unsigned" \
    'release workflow must select the exact versioned ZIP'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    'environment: plugin-signing' \
    'signing secrets must be scoped to the plugin-signing environment'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    "PRIVATE_KEY: \${{ secrets.PRIVATE_KEY }}" \
    'signing job must receive the private key from an environment secret'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    "PRIVATE_KEY_PASSWORD: \${{ secrets.PRIVATE_KEY_PASSWORD }}" \
    'signing job must receive the private-key password from an environment secret'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    "CERTIFICATE_CHAIN: \${{ secrets.CERTIFICATE_CHAIN }}" \
    'signing job must receive the certificate chain from an environment secret'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    "printf '%s' \"\$CERTIFICATE_CHAIN\" | base64 --decode > build/signing/chain.crt" \
    'signing job must decode the certificate only into its ephemeral runner workspace'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    "printf '%s' \"\$PRIVATE_KEY\" | base64 --decode > build/signing/private.pem" \
    'signing job must decode the private key only into its ephemeral runner workspace'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    './gradlew --offline --dependency-verification=strict --no-daemon signPlugin verifyPluginSignature' \
    'signing job must sign and verify the exact transferred ZIP'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    'verifySignPluginIsolation dependencies' \
    'signing job must verify signing isolation before exposing secrets'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    '--configuration marketplaceZipSigner' \
    'signing job must resolve its complete signing toolchain before exposing secrets'
  require_job_order "$repository_root/.github/workflows/release.yml" sign \
    'Resolve and verify the signing toolchain before exposing secrets' \
    'Sign and verify exact release ZIP' \
    'signing toolchain verification must occur before the secret-bearing step'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    "scripts/validate-release.sh archive \"\$RELEASE_VERSION\" signed" \
    'signing job must select the exact signed ZIP'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    'sha256sum --check' \
    'release workflow must verify the ZIP checksum'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    'actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6' \
    'signing job must attest the signed ZIP and checksum'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a' \
    'release workflow must retain the downloadable files'
  require_job_literal "$repository_root/.github/workflows/release.yml" sign \
    'retention-days: 90' \
    'signed release ZIP and checksum must be retained for 90 days'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    'needs: [build, sign]' \
    'GitHub release publication must wait for verification and signing'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    'contents: write' \
    'GitHub release job must receive only the repository-content write permission it needs'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    'actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c' \
    'GitHub release job must download the retained signed artifact'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    "name: alarm-and-timer-\${{ needs.build.outputs.version }}" \
    'GitHub release job must consume the exact artifact produced by signing'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    "gh release create \"\$RELEASE_VERSION\"" \
    'tag workflow must automatically create a GitHub release'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    '--verify-tag' \
    'GitHub release creation must require the existing authorized tag'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    '--draft' \
    'GitHub release must remain a draft until uploaded assets are verified'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    "gh release upload \"\$RELEASE_VERSION\" \"\$archive\" \"\$checksum\" --clobber" \
    'GitHub release must attach the signed ZIP and checksum'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    'sha256sum --check' \
    'GitHub release job must verify the signed ZIP checksum'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    "cmp -s \"\$archive\" \"\$downloaded/\$archive_name\"" \
    'GitHub release job must byte-compare the uploaded signed ZIP'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    "cmp -s \"\$checksum\" \"\$downloaded/\$checksum_name\"" \
    'GitHub release job must byte-compare the uploaded checksum'
  require_job_literal "$repository_root/.github/workflows/release.yml" release \
    "gh release edit \"\$RELEASE_VERSION\" --draft=false --latest" \
    'GitHub release must publish only after its assets pass verification'
  if grep -Eq 'PUBLISH_TOKEN|publishPlugin' \
    "$repository_root/.github/workflows/release.yml"; then
    contract_failure 'release workflow must not access Marketplace publishing credentials or tasks'
  fi
  require_immutable_action_pins "$repository_root/.github/workflows/ci.yml"
  require_immutable_action_pins "$repository_root/.github/workflows/release.yml"
  if grep -Eq 'secrets\.' "$repository_root/.github/workflows/ci.yml"; then
    contract_failure 'CI must not access repository or environment secrets'
  fi
  require_signing_secret_isolation "$repository_root/.github/workflows/release.yml"
  require_job_literal "$repository_root/.github/workflows/ci.yml" build-test-verify \
    'Reclaim disk space for IDE verification' \
    'CI must reclaim runner space for the complete IDE verification matrix'
  require_job_literal "$repository_root/.github/workflows/ci.yml" build-test-verify \
    '/usr/local/lib/android' \
    'CI disk cleanup must remove the unused Android SDK'
  require_job_literal "$repository_root/.github/workflows/ci.yml" build-test-verify \
    '/opt/hostedtoolcache/CodeQL' \
    'CI disk cleanup must remove the unused CodeQL bundle'
  require_job_order "$repository_root/.github/workflows/ci.yml" build-test-verify \
    'Reclaim disk space for IDE verification' \
    'Verify release candidate' \
    'CI must reclaim runner space before IDE verification'
  require_job_literal "$repository_root/.github/workflows/ci.yml" build-test-verify \
    'Rehearse plugin signing with an ephemeral non-production certificate' \
    'CI must execute the non-production signing rehearsal'

  require_literal "$repository_root/README.md" \
    'jetbrains-<pluginVersion>-signed.zip.sha256' \
    'release guide must identify the signed ZIP checksum'
  require_literal "$repository_root/README.md" \
    'GitHub Actions' \
    'release guide must explain where to download the workflow artifact'
  require_literal "$repository_root/README.md" \
    'Upload the signed ZIP manually' \
    'release guide must describe manual Marketplace upload'
  require_literal "$repository_root/README.md" \
    'The workflow automatically creates the GitHub release but never publishes to JetBrains Marketplace.' \
    'release guide must distinguish automatic GitHub releases from manual Marketplace publishing'
  require_literal "$repository_root/README.md" \
    'Do not add a Marketplace publishing token.' \
    'release guide must state the Marketplace credential boundary'
}

validate_release_contract

new_contract_fixture() {
  local name=$1
  local fixture="$temporary_root/contract-$name"

  mkdir -p \
    "$fixture/.github/workflows" \
    "$fixture/gradle" \
    "$fixture/src/main/resources/META-INF" \
    "$fixture/src/main/resources/messages"
  cp "$repository_root/build.gradle.kts" "$fixture/build.gradle.kts"
  cp "$repository_root/.github/workflows/release.yml" "$fixture/.github/workflows/release.yml"
  cp "$repository_root/.github/workflows/ci.yml" "$fixture/.github/workflows/ci.yml"
  cp "$repository_root/MARKETPLACE_CHANGE_NOTES.html" "$fixture/MARKETPLACE_CHANGE_NOTES.html"
  cp "$repository_root/README.md" "$fixture/README.md"
  cp "$repository_root/gradle/verification-metadata.xml" "$fixture/gradle/verification-metadata.xml"
  cp "$repository_root/gradle.properties" "$fixture/gradle.properties"
  cp "$repository_root/src/main/resources/META-INF/plugin.xml" \
    "$fixture/src/main/resources/META-INF/plugin.xml"
  cp "$repository_root/src/main/resources/messages/AlarmTimerBundle.properties" \
    "$fixture/src/main/resources/messages/AlarmTimerBundle.properties"
  printf '%s\n' "$fixture"
}

expect_contract_failure() {
  local description=$1
  local expected=$2
  local fixture=$3
  local output="$temporary_root/contract-failure.out"

  if (repository_root="$fixture"; validate_release_contract) >"$output" 2>&1; then
    printf 'expected contract failure: %s\n' "$description" >&2
    exit 1
  fi
  grep -Fq -- "$expected" "$output" || {
    printf 'wrong contract failure: %s\n' "$description" >&2
    cat "$output" >&2
    exit 1
  }
}

contract_fixture=$(new_contract_fixture incomplete-gate)
sed -i.bak \
  's|./gradlew --dependency-verification=strict clean verifyReleaseCandidate|./gradlew test|' \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'release workflow skips the complete gate' \
  'release workflow must run the complete release-candidate gate' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture publishing-task)
sed -i.bak \
  '/signPlugin verifyPluginSignature/a\
          ./gradlew publishPlugin' \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'release workflow adds Marketplace publishing' \
  'release workflow must not access Marketplace publishing credentials or tasks' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture build-job-signing-secret)
sed -i.bak \
  "/timeout-minutes: 90/a\\
    PRIVATE_KEY: \${{ secrets.PRIVATE_KEY }}" \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'unsigned build job receives a signing secret' \
  'signing secrets must appear only inside the isolated sign job' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture mutable-ci-action)
sed -i.bak \
  's|actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1|actions/checkout@v7|' \
  "$contract_fixture/.github/workflows/ci.yml"
rm "$contract_fixture/.github/workflows/ci.yml.bak"
expect_contract_failure \
  'CI action uses a mutable version tag' \
  'every external action in .github/workflows/ci.yml must use a full 40-character commit SHA' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture workflow-level-signing-secret)
sed -i.bak \
  "/^permissions:\$/a\\
env:\\
  PRIVATE_KEY: \${{ secrets.PRIVATE_KEY }}" \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'workflow-level environment exposes a signing secret' \
  'signing secrets must appear only inside the isolated sign job' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture short-retention)
sed -i.bak \
  's/retention-days: 90/retention-days: 1/' \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'release artifact retention is shortened' \
  'signed release ZIP and checksum must be retained for 90 days' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture missing-attestation)
sed -i.bak \
  's|actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6|actions/attest@missing|' \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'release provenance attestation is removed' \
  'signing job must attest the signed ZIP and checksum' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture unauthorized-actor)
sed -i.bak \
  "s/github.actor == 'ozdemirburak'/github.actor == 'any-writer'/" \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'release workflow allows an unauthorized tag actor' \
  'release workflow must restrict tag authorization to the designated maintainer account' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture missing-github-release-publication)
sed -i.bak \
  "s|gh release edit \"\$RELEASE_VERSION\" --draft=false --latest|echo \"release remains a draft\"|" \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'release workflow leaves the GitHub release unpublished' \
  'GitHub release must publish only after its assets pass verification' \
  "$contract_fixture"

contract_fixture=$(new_contract_fixture commented-sign-command)
sed -i.bak \
  's|          ./gradlew --offline --dependency-verification=strict --no-daemon signPlugin verifyPluginSignature|          # ./gradlew --offline --dependency-verification=strict --no-daemon signPlugin verifyPluginSignature|' \
  "$contract_fixture/.github/workflows/release.yml"
rm "$contract_fixture/.github/workflows/release.yml.bak"
expect_contract_failure \
  'signing command survives only as a comment' \
  'signing job must sign and verify the exact transferred ZIP' \
  "$contract_fixture"

new_fixture() {
  local name=$1
  local fixture="$temporary_root/$name"

  mkdir -p "$fixture"
  cat >"$fixture/gradle.properties" <<'EOF'
pluginVersion=1.0.0
pluginRepositoryUrl=https://github.com/onlinealarmkur/jetbrains-alarm-timer
EOF
  cat >"$fixture/README.md" <<'EOF'
# Fixture

## Changelog

### [1.0.0] - 2026-07-15

[1.0.0]: https://github.com/onlinealarmkur/jetbrains-alarm-timer/releases/tag/1.0.0
EOF
  cat >"$fixture/MARKETPLACE_CHANGE_NOTES.html" <<'EOF'
<!-- version: 1.0.0 -->
<p>Fixture release notes.</p>
EOF
  git -C "$fixture" init --quiet
  git -C "$fixture" add gradle.properties README.md MARKETPLACE_CHANGE_NOTES.html
  git -C "$fixture" -c user.name='Release Validator' -c user.email='release-validator@example.invalid' commit --quiet -m fixture
  printf '%s\n' "$fixture"
}

expect_failure() {
  local description=$1
  shift
  if "$@" >"$temporary_root/failure.out" 2>&1; then
    printf 'expected failure: %s\n' "$description" >&2
    exit 1
  fi
}

fixture=$(new_fixture positive)
"$validator" identity 1.0.0 "$fixture"

expect_failure "dispatch and Gradle versions differ" \
  "$validator" identity 1.0.1 "$fixture"
expect_failure "version has a leading zero" \
  "$validator" identity 01.0.0 "$fixture"

fixture=$(new_fixture changelog-heading)
sed -i.bak 's/### \[1.0.0\] - 2026-07-15/### [1.0.0]/' "$fixture/README.md"
rm "$fixture/README.md.bak"
expect_failure "changelog heading is not dated" \
  "$validator" identity 1.0.0 "$fixture"

fixture=$(new_fixture changelog-link)
sed -i.bak 's|releases/tag/1.0.0|releases/tag/9.9.9|' "$fixture/README.md"
rm "$fixture/README.md.bak"
expect_failure "changelog link differs" \
  "$validator" identity 1.0.0 "$fixture"

fixture=$(new_fixture local-tag)
git -C "$fixture" tag 1.0.0
expect_failure "local release tag exists" \
  "$validator" identity 1.0.0 "$fixture"
"$validator" tagged-identity 1.0.0 "$fixture"

fixture=$(new_fixture missing-tag)
expect_failure "tagged release identity has no tag" \
  "$validator" tagged-identity 1.0.0 "$fixture"

fixture=$(new_fixture tag-on-different-commit)
git -C "$fixture" tag 1.0.0
touch "$fixture/later.txt"
git -C "$fixture" add later.txt
git -C "$fixture" -c user.name='Release Validator' -c user.email='release-validator@example.invalid' \
  commit --quiet -m later
expect_failure "tagged release identity points to another commit" \
  "$validator" tagged-identity 1.0.0 "$fixture"

fixture=$(new_fixture change-notes-missing)
rm "$fixture/MARKETPLACE_CHANGE_NOTES.html"
expect_failure "marketplace change notes file is missing" \
  "$validator" identity 1.0.0 "$fixture"

fixture=$(new_fixture change-notes-version)
sed -i.bak 's/<!-- version: 1.0.0 -->/<!-- version: 1.0.1 -->/' \
  "$fixture/MARKETPLACE_CHANGE_NOTES.html"
rm "$fixture/MARKETPLACE_CHANGE_NOTES.html.bak"
expect_failure "marketplace change notes declare a different version" \
  "$validator" identity 1.0.0 "$fixture"

fixture=$(new_fixture change-notes-duplicate-header)
printf '<!-- version: 1.0.0 -->\n' >>"$fixture/MARKETPLACE_CHANGE_NOTES.html"
expect_failure "marketplace change notes repeat the version header" \
  "$validator" identity 1.0.0 "$fixture"

fixture=$(new_fixture change-notes-blank-body)
printf '<!-- version: 1.0.0 -->\n\n' >"$fixture/MARKETPLACE_CHANGE_NOTES.html"
expect_failure "marketplace change notes body is blank" \
  "$validator" identity 1.0.0 "$fixture"

fixture=$(new_fixture dirty-worktree)
touch "$fixture/untracked.txt"
expect_failure "release worktree is dirty" \
  "$validator" identity 1.0.0 "$fixture"

fixture=$(new_fixture archives)
mkdir -p "$fixture/build/distributions"
touch "$fixture/build/distributions/jetbrains-1.0.0.zip"
"$validator" archive 1.0.0 unsigned "$fixture"
rm "$fixture/build/distributions/jetbrains-1.0.0.zip"
touch "$fixture/build/distributions/jetbrains-1.0.0-signed.zip"
"$validator" archive 1.0.0 signed "$fixture"
mv "$fixture/build/distributions/jetbrains-1.0.0-signed.zip" \
  "$fixture/build/distributions/jetbrains-9.9.9-signed.zip"
expect_failure "signed archive version differs" \
  "$validator" archive 1.0.0 signed "$fixture"

printf 'release validation tests passed\n'
