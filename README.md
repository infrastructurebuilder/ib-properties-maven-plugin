# Infrastructurebuider Properties Maven Plugin

This is a fork of [the Mojohaus plugin](http://www.mojohaus.org/properties-maven-plugin/)

Original README is below the fold.

Functionality of IB versions will be _mostly_ similar to existing upstream versions. `#YMMV`

## Branching

This fork attempts to ensure changes from the upstream are present.  "Sync fork", etc, should
be applied.

NO MODIFICATIONS SHOULD BE MADE TO `	master` except from upstream.

IB changes are made with git-flow to `develop`, so `develop` needs to be regularly rebased from
`master` which has been sync'd with the upstream.

## Workflow

1. Sync `master` from upstream.
2. Rebase `develop` from `master`.
3. Branch from `develop` for a feature working branch (e.g. `git flow feature start BLAH`)
4. Do work.
5. PR to `develop`, with squashed commits.  PRs to develop should
  1. Not be merged until you are confident it is correct and tested (obvs).
  2. Be a single feature commit onto `develop`.
6. Collect work on `develop`, ensuring that rebasing from `master`/upstream is managed.

## Releases

The releases for the IB version will be done slightly off-schedule from upstream changes.  For the
most part, this downstream version will be keyed to the upstream's version with an additional
number in the version.   Thus, `1.2.1.7-ib` of the IB version is based on _some_ version upstream that
was called `1.2.1-SNAPSHOT` at the time of the merge.  If the upstream version changes, then this
project's version will change accordingly and the final number set to `.0` again.

1. Create a PR from `develop` to `ibrelease`.  Do NOT squash this PR.
2. Get acceptance for release PR.
3. Do `mvn -B release:prepare release:perform` with IB's release setup from `ibrelease`.
1. Merge changes from `ibrelease` back onto `develop`.
1. Lather
1. Rinse
1. Repeat

IB Releases are made from the /ibreleases branch, which is downstream from `develop`.

---

# MojoHaus Properties Maven Plugin

This is the [properties-maven-plugin](http://www.mojohaus.org/properties-maven-plugin/).

[![Maven Central](https://img.shields.io/maven-central/v/org.codehaus.mojo/properties-maven-plugin.svg?label=Maven%20Central)](https://search.maven.org/artifact/org.codehaus.mojo/properties-maven-plugin)
[![GitHub CI](https://github.com/mojohaus/properties-maven-plugin/actions/workflows/maven.yml/badge.svg)](https://github.com/mojohaus/properties-maven-plugin/actions/workflows/maven.yml)
## Releasing

* Make sure `gpg-agent` is running.
* Execute `mvn -B release:prepare release:perform`

For publishing the site do the following:

```
cd target/checkout
mvn verify site site:stage scm-publish:publish-scm
```
