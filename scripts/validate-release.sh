#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf 'release validation failed: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/validate-release.sh identity VERSION [REPOSITORY]
  scripts/validate-release.sh tagged-identity VERSION [REPOSITORY]
  scripts/validate-release.sh archive VERSION unsigned|signed [REPOSITORY]
EOF
  exit 2
}

validate_version() {
  local version=$1
  [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
    fail "version '$version' is not a supported stable SemVer (MAJOR.MINOR.PATCH without leading zeroes)"
}

require_single_property() {
  local file=$1
  local property=$2
  local matches
  matches=$(sed -n "s/^${property}=//p" "$file")
  [[ -n "$matches" && "$matches" != *$'\n'* ]] ||
    fail "$file must contain exactly one $property property"
  printf '%s\n' "$matches"
}

validate_change_notes() {
  local version=$1
  local notes=$2
  local header_pattern='^[[:space:]]*<!-- version: (.+) -->[[:space:]]*$'
  local header_line header_count declared body

  [[ -f "$notes" ]] || fail "missing $notes"

  header_line=$(awk 'NF { print; exit }' "$notes")
  [[ "$header_line" =~ $header_pattern ]] ||
    fail "$notes must start with '<!-- version: $version -->'"

  declared=${BASH_REMATCH[1]}
  [[ "$declared" == "$version" ]] ||
    fail "$notes declares version '$declared' but the release version is '$version'"

  header_count=$(grep -Ec "$header_pattern" "$notes" || true)
  [[ "$header_count" -eq 1 ]] ||
    fail "$notes must contain exactly one version header line, found $header_count"

  body=$(awk 'NF && !seen { seen = 1; next } seen' "$notes")
  [[ -n "${body//[[:space:]]/}" ]] ||
    fail "$notes must contain a non-blank change-notes body after the version header"
}

validate_identity() {
  local version=$1
  local repository=$2
  local tag_state=$3
  local properties="$repository/gradle.properties"
  local changelog="$repository/README.md"
  local change_notes="$repository/MARKETPLACE_CHANGE_NOTES.html"
  local configured_version repository_url tag version_pattern tag_commit head_commit

  validate_version "$version"
  git -C "$repository" rev-parse --git-dir >/dev/null 2>&1 ||
    fail "$repository is not a Git repository"
  [[ -f "$properties" ]] || fail "missing $properties"
  [[ -f "$changelog" ]] || fail "missing $changelog"

  configured_version=$(require_single_property "$properties" pluginVersion)
  [[ "$configured_version" == "$version" ]] ||
    fail "release version '$version' does not match pluginVersion '$configured_version'"

  repository_url=$(require_single_property "$properties" pluginRepositoryUrl)
  repository_url=${repository_url%/}
  version_pattern=${version//./\\.}
  grep -Eq "^### \[$version_pattern\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$" "$changelog" ||
    fail "README.md changelog has no dated heading for [$version]"
  [[ $(grep -Ec "^### \[$version_pattern\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$" "$changelog") -eq 1 ]] ||
    fail "README.md changelog must contain exactly one dated heading for [$version]"
  grep -Fqx "[$version]: $repository_url/releases/tag/$version" "$changelog" ||
    fail "README.md release link for [$version] must target $repository_url/releases/tag/$version"

  validate_change_notes "$version" "$change_notes"

  tag="$version"
  case "$tag_state" in
    absent)
      if git -C "$repository" show-ref --verify --quiet "refs/tags/$tag"; then
        fail "release tag $tag already exists"
      fi
      ;;
    current)
      git -C "$repository" show-ref --verify --quiet "refs/tags/$tag" ||
        fail "release tag $tag does not exist"
      tag_commit=$(git -C "$repository" rev-parse "$tag^{commit}")
      head_commit=$(git -C "$repository" rev-parse HEAD)
      [[ "$tag_commit" == "$head_commit" ]] ||
        fail "release tag $tag points to $tag_commit instead of checked-out commit $head_commit"
      ;;
    *)
      fail "unsupported release tag state '$tag_state'"
      ;;
  esac
  [[ -z "$(git -C "$repository" status --porcelain --untracked-files=all)" ]] ||
    fail "$repository must be a clean Git worktree before release"

  printf 'release identity validated: version=%s tag=%s\n' "$version" "$tag"
}

validate_archive() {
  local version=$1
  local kind=$2
  local repository=$3
  local distributions="$repository/build/distributions"
  local expected suffix
  local -a candidates

  validate_version "$version"
  [[ "$kind" == "unsigned" || "$kind" == "signed" ]] || usage
  [[ -d "$distributions" ]] || fail "missing $distributions"

  suffix=""
  [[ "$kind" == "signed" ]] && suffix="-signed"
  expected="$distributions/jetbrains-$version$suffix.zip"

  shopt -s nullglob
  if [[ "$kind" == "signed" ]]; then
    candidates=("$distributions"/*-signed.zip)
  else
    candidates=("$distributions"/*.zip)
  fi
  shopt -u nullglob

  [[ ${#candidates[@]} -eq 1 ]] ||
    fail "expected exactly one $kind plugin ZIP, found ${#candidates[@]}"
  [[ "${candidates[0]}" == "$expected" ]] ||
    fail "expected $expected, found ${candidates[0]}"

  printf '%s\n' "$expected"
}

[[ $# -ge 2 ]] || usage
command=$1
version=$2

case "$command" in
  identity)
    [[ $# -le 3 ]] || usage
    validate_identity "$version" "${3:-.}" absent
    ;;
  tagged-identity)
    [[ $# -le 3 ]] || usage
    validate_identity "$version" "${3:-.}" current
    ;;
  archive)
    [[ $# -ge 3 && $# -le 4 ]] || usage
    validate_archive "$version" "$3" "${4:-.}"
    ;;
  *)
    usage
    ;;
esac
