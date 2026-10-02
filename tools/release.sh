#!/usr/bin/env bash
# Publishes a new version of the laptop side of Curfew.
#   1. put the new version number in agent/VERSION (it must go up, or apt sees no update)
#   2. tools/release.sh           builds the package and publishes it
#      tools/release.sh build     only builds dist/ (the package and the apt folder), publishes nothing
# Publishing does two things: the apt folder goes to the gh-pages branch (GitHub Pages serves it;
# that is what "sudo apt update" reads), and the package and the app go on a GitHub release.
# The apt folder is signed with the key in .apt-key/ (not in git; keep a copy of that folder).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(tr -d '[:space:]' < agent/VERSION)"
export GNUPGHOME="$ROOT/.apt-key"
[ -d "$GNUPGHOME" ] || { echo "The signing key folder .apt-key/ is missing."; exit 1; }

rm -rf dist && mkdir -p dist/site/apt
DEB="$(agent/build-deb.sh dist)"
cp "$DEB" dist/curfew.deb                       # a name without the version, for the "latest" download link
cp "$DEB" dist/site/apt/
(
  cd dist/site/apt
  dpkg-scanpackages --multiversion . > Packages 2>/dev/null
  gzip -9 -k -n Packages
  {
    echo "Origin: Curfew"
    echo "Label: Curfew"
    echo "Suite: stable"
    echo "Date: $(date -Ru)"
    echo "Description: Curfew, the laptop side"
    echo "SHA256:"
    for f in Packages Packages.gz; do
      printf ' %s %s %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$(stat -c %s "$f")" "$f"
    done
  } > Release
  gpg --batch --yes --clearsign -o InRelease Release
  gpg --batch --yes --armor --detach-sign -o Release.gpg Release
)
cat > dist/site/index.html <<HTML
<!doctype html><meta charset="utf-8"><title>Curfew</title>
<p>This address holds the package source for <a href="https://github.com/sharjeelmazhar/Curfew">Curfew</a> (version $VERSION).
Install instructions are in the README there.</p>
HTML
touch dist/site/.nojekyll
echo "Built $DEB and dist/site/apt"
[ "${1:-}" = build ] && exit 0

if gh release view "v$VERSION" >/dev/null 2>&1; then
  echo "Version $VERSION is already released. Raise the number in agent/VERSION first."; exit 1
fi
URL="$(git remote get-url origin)"
(
  cd dist/site
  git init -q -b gh-pages
  git add -A
  git -c user.name="$(git -C "$ROOT" config user.name)" -c user.email="$(git -C "$ROOT" config user.email)" \
    commit -q -m "Curfew $VERSION"
  git push -q -f "$URL" gh-pages
)
rm -rf dist/site/.git
gh release create "v$VERSION" "$DEB" dist/curfew.deb Curfew.apk --target "$(git rev-parse HEAD)" \
  --title "Curfew $VERSION" --notes-file "${NOTES:-/dev/null}"
echo "Published $VERSION."
