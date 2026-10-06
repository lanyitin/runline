#!/bin/sh
# Runs inside the fixed build platform (release/Dockerfile): builds the commit twice, each time from
# a fresh clone (so no build output, no node_modules, no Gradle project cache, no npm cache of the
# first build is there for the second), and leaves the jars of each build in /out/first and
# /out/second for `compareReleaseBuilds`. The Gradle user home is shared by the two builds: it only
# holds the dependencies downloaded from Maven Central and the Gradle distribution, which are
# immutable by version.
#
# /out/source.bundle is the commit (git bundle of HEAD). Nothing here compares anything.
set -eu

export GRADLE_USER_HOME=/tmp/gradle-home

for build in first second; do
  work="/tmp/build-$build"
  rm -rf "$work"
  git clone --quiet /out/source.bundle "$work"
  cd "$work"
  echo "== $build build of $(git rev-parse HEAD)"
  NPM_CONFIG_CACHE="/tmp/npm-cache-$build" \
    ./gradlew --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process \
      :engine:engineDistribution -Prunline.release=true
  mkdir -p "/out/$build"
  cp -R engine/build/engine-dist/. "/out/$build/"
  cd /
  rm -rf "$work"
done
