#!/usr/bin/env bash
# One-shot: turns the Example Carrier template into a plugin for another carrier (spec 16 sections 2.2, 4.3 and 11).
#
#   scripts/rename.sh <slug> "<Display name>"
#
# Run it once, in a fresh copy of the template, before you change anything else. It renames in place:
#   provider id            example                          -> <slug>
#   plugin id / jar name   pano-plugin-market-shipping-example -> pano-plugin-market-shipping-<slug>
#   root package           com.panomc.plugins.marketship.example -> com.panomc.plugins.marketship.<pkg>  (<pkg> = slug without hyphens)
#   classes                Example*                          -> <Cls>*                                    (<Cls> = slug in PascalCase)
#   display name           Example Carrier                   -> <Display name>
# and rewrites package.json, .releaserc.json, store/store.json, gradle.properties (description, source URL), README.md
# (a short stub) and AGENT.md (placeholders), and removes the template-only `rename-smoke` job from .github/workflows/ci.yml.
# build.gradle.kts, the license package, LICENSE, gradle/ and scripts/ are never touched.
# Needs bash and GNU sed (Linux, WSL, Git Bash).
set -euo pipefail

usage() {
  echo "usage: scripts/rename.sh <slug> \"<Display name>\"" >&2
  echo "  slug: lower-case letters, digits and single hyphens, 2-20 characters, starting with a letter" >&2
  exit 2
}

[ $# -eq 2 ] || usage
slug=$1
display=$2

die() { echo "rename.sh: $*" >&2; exit 1; }

# ---- validate ---------------------------------------------------------------------------------------------------------
[[ "$slug" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+)*$ ]] || die "slug '$slug' must match [a-z][a-z0-9]*(-[a-z0-9]+)* (it becomes a Kotlin package and class name, so it starts with a letter)"
[ ${#slug} -ge 2 ] && [ ${#slug} -le 20 ] || die "slug '$slug' must be 2 to 20 characters long (the plugin id pano-plugin-market-shipping-<slug> may have at most 48)"
[[ ! "$slug" =~ -v[0-9] ]] || die "slug '$slug' must not contain '-v<digit>' (it would break the <slug>-v<version> git tags)"
[ "$slug" != "example" ] || die "the slug 'example' is the template itself; pick the carrier's name"
[ -n "$display" ] || die "the display name must not be empty"
[ ${#display} -le 40 ] || die "the display name must be at most 40 characters (the store name 'Market Shipping: <name>' is limited to 64)"
case "$display" in
  *'"'*|*'\'*|*'/'*|*'|'*|*'$'*|*'`'*|*$'\n'*|*$'\r'*|*$'\t'*) die "the display name must not contain  \" \\ / | \$ \` or control characters" ;;
esac

cd "$(dirname "$0")/.."
[ -f gradle.properties ] && [ -d src/main/kotlin/com/panomc/plugins/marketship/example ] || die "this does not look like an unrenamed template (no src/main/kotlin/com/panomc/plugins/marketship/example)"

pluginId="pano-plugin-market-shipping-$slug"
pkg=${slug//-/}
cls=""
IFS='-' read -ra parts <<< "$slug"
for part in "${parts[@]}"; do cls+="$(tr '[:lower:]' '[:upper:]' <<< "${part:0:1}")${part:1}"; done

# sed replacement text: escape the characters that are special on the right-hand side
esc() { printf '%s' "$1" | sed -e 's/[&\\|]/\\&/g'; }
displayEsc=$(esc "$display")

echo "slug=$slug  plugin id=$pluginId  package=com.panomc.plugins.marketship.$pkg  class prefix=$cls  display name=$display"

# ---- move the source folders and rename the files -----------------------------------------------------------------------
for set in main test; do
  from="src/$set/kotlin/com/panomc/plugins/marketship/example"
  to="src/$set/kotlin/com/panomc/plugins/marketship/$pkg"
  [ -d "$from" ] || continue
  mv "$from" "$to"
  while IFS= read -r file; do
    base=$(basename "$file")
    if [[ "$base" == Example*.kt ]]; then mv "$file" "$(dirname "$file")/${cls}${base#Example}"; fi
  done < <(find "$to" -type f -name 'Example*.kt')
done

# ---- text replacement (order is normative, spec 16 section 4.3) -----------------------------------------------------------
rewrite() {
  sed -i \
    -e "s|pano-plugin-market-shipping-example|$pluginId|g" \
    -e "s|marketship\\.example|marketship.$pkg|g" \
    -e "s|Example Carrier|$displayEsc|g" \
    -e "s|Example|$cls|g" \
    -e "s|\"example\"|\"$slug\"|g" \
    "$1"
}

while IFS= read -r file; do
  rewrite "$file"
done < <(find src store .github settings.gradle.kts gradle.properties package.json .releaserc.json VERIFICATION.md \
           -type f \( -name '*.kt' -o -name '*.json' -o -name '*.conf' -o -name '*.md' -o -name '*.yml' -o -name '*.html' -o -name '*.kts' -o -name '*.properties' \) \
           ! -path 'src/main/kotlin/com/panomc/plugins/license/*' 2>/dev/null)

# ---- the template's own CI job -------------------------------------------------------------------------------------------------
# `rename-smoke` renames a copy of the unrenamed template; in a renamed repository it would hit the guard above and fail on every
# push. Drop that job (and any blank line it leaves behind) from the workflow; the job `build` stays.
if [ -f .github/workflows/ci.yml ]; then
  awk '
    /^  rename-smoke:/ { skip = 1; next }
    skip && /^  [A-Za-z0-9_-]+:/ { skip = 0 }
    skip && /^[^ ]/ { skip = 0 }
    !skip { print }
  ' .github/workflows/ci.yml | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' > .github/workflows/ci.yml.tmp
  mv .github/workflows/ci.yml.tmp .github/workflows/ci.yml
fi
! grep -rqs 'rename-smoke' .github/workflows || die "could not remove the rename-smoke job from .github/workflows"

# ---- files that are rewritten rather than substituted ------------------------------------------------------------------------
sed -i \
  -e "s|^pluginName=.*|pluginName=Market Shipping: $displayEsc|" \
  -e "s|^pluginDescription=.*|pluginDescription=$displayEsc shipping for Pano Market.|" \
  -e "s|^pluginSourceUrl=.*|pluginSourceUrl=|" \
  gradle.properties

cat > package.json <<JSON
{
  "name": "$slug",
  "private": true,
  "version": "0.0.0"
}
JSON

# A standalone repository releases with plain semantic-release; the upload to the store is the optional second step.
cat > .releaserc.json <<JSON
{
  "branches": [{ "name": "dev", "prerelease": true }, "main"],
  "plugins": [
    "@semantic-release/commit-analyzer",
    "@semantic-release/release-notes-generator",
    ["@PanoMC/semantic-release-pano", {
      "file": "build/libs/$pluginId-\${version}.jar",
      "panoVersion": "1.0.0",
      "configs": [
        { "resourceId": "$pluginId", "panoUrl": "https://api-dev.panomc.com", "tokenVar": "PANO_TOKEN", "branches": ["dev"] },
        { "resourceId": "$pluginId", "panoUrl": "https://api.panomc.com", "tokenVar": "PANO_PROD_TOKEN", "branches": ["main"] }
      ]
    }],
    ["@semantic-release/github", {
      "assets": [{ "path": "build/libs/$pluginId-*.jar", "label": false }],
      "successComment": false, "failComment": false, "releasedLabels": false
    }]
  ]
}
JSON

cat > README.md <<MD
# $display for Pano Market

Shipping provider plugin \`$pluginId\` (provider id \`$slug\`) for [Pano Market](https://panomc.com), created from the
Pano Market shipping template with \`scripts/rename.sh\`.

\`\`\`
./gradlew build      # compile, test, build and verify build/libs/$pluginId-<version>.jar
\`\`\`

Read \`AGENT.md\` for what to implement and in which order. The class layout, the gates of the build and the rules a provider
must keep are the ones of the template; its README (https://github.com/PanoMC/pano-plugin-market-shipping-template) explains them.

The code still talks to the imaginary "Example Carrier" protocol of the template (\`$cls*.kt\`, \`src/test/resources/vectors\`):
replace it with the real carrier protocol, then update \`VERIFICATION.md\`, \`store/description.html\` and the locale files.
MD

# AGENT.md holds placeholders instead of names
sed -i \
  -e "s|<Display name>|$displayEsc|g" \
  -e "s|<slug>|$slug|g" \
  -e "s|<plugin id>|$pluginId|g" \
  -e "s|<package>|com.panomc.plugins.marketship.$pkg|g" \
  -e "s|<Cls>|$cls|g" \
  AGENT.md

echo "done. Next: ./gradlew build, then replace the example protocol with the carrier's (see AGENT.md)."
