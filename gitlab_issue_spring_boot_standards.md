# 🏗️ Implement Spring Boot Microservice Coding Standards & Engineering Quality Baseline

**Labels:** `engineering-standards` `dx` `ci-cd` `testing` `static-analysis` `java17` `sprint::current`
**Milestone:** v1.0 Quality Baseline · **Due:** Apr 11, 2026 · **Weight:** 13 · **Priority:** High
**Assignees:** @lead.engineer · @devops.eng

---

## 📋 Background

Our Spring Boot microservice project currently lacks a unified, enforced engineering quality baseline. Without standardized tooling configuration, formatting rules, and CI pipeline guards, code quality will diverge across contributors, onboarding time increases, and defects are caught too late in the delivery cycle.

The **Spring Boot Standards Guide v1.0** has been drafted and defines the full stack of quality tooling we intend to adopt: Google Java Format, Checkstyle, SpotBugs, JaCoCo, SonarQube, a 6-stage GitLab CI pipeline, and matching VS Code workspace configuration.

---

## 🎯 Objective

> **Goal:** Scaffold and wire every tool referenced in the standards guide into the repository so that _all enforcement is automated_ — no manual review step required for formatting, naming, or coverage thresholds.

This issue tracks the full implementation: from repository scaffold files (`.editorconfig`, `lombok.config`, VS Code workspace) through Maven plugin configuration, GitLab CI pipeline stages, and SonarQube integration.

---

## 📦 Scope

The following areas are in scope for this issue:

- **Repository scaffold** — all config files committed to the repo root and `.vscode/`
- **Maven plugins** — fmt-maven-plugin, Checkstyle, SpotBugs, JaCoCo, Failsafe wired into `pom.xml`
- **Build profiles** — `default`, `ci`, and `fast` profiles configured and validated
- **GitLab CI pipeline** — all 6 stages defined in `.gitlab-ci.yml` with correct job dependencies
- **SonarQube** — project configured, CI variables documented, quality gate passing
- **Developer workflow** — pre-commit hook, MR template, and VS Code prompt-to-install working end-to-end

---

## ⚠️ Gap Analysis (Standards Doc Review)

The v1.0 document is a strong foundation. The following gaps were identified during technical review:

| Severity | Area | Finding | Recommendation |
|----------|------|---------|----------------|
| 🔴 High | Section 2 — Scaffold files | Section references scaffold files but no file contents are provided (`application.yml`, `hooks/pre-commit`, etc.) | Add annotated file contents for each scaffold entry, or link to the committed repo path |
| 🔴 High | Section 6 — CI Pipeline | No caching strategy defined for Maven `.m2` repository — each pipeline run will re-download all dependencies | Add a `cache:` block in `.gitlab-ci.yml` keyed on `pom.xml` hash |
| 🔴 High | Architecture tests | ArchUnit is listed in the test classification table but no rules are defined — the test class will be empty | Define layered architecture rule, no circular dependencies, no `*.impl` package leakage |
| 🔴 High | General — Plugin versions | Plugin versions are not pinned anywhere in the document — non-reproducible builds will result | Add a `<properties>` block to `pom.xml` pinning all plugin versions; enforce with `requirePluginVersions` |
| 🟠 Medium | Section 3.3 — Checkstyle | Cyclomatic complexity max of 12 is lenient for a microservice; clean code industry baseline is 10 | Lower to 10 or document an explicit exception policy |
| 🟠 Medium | Section 5.2 — Coverage | 80%/70% thresholds are not enforced during local builds — developers only discover violations post-push | Bind `jacoco:check` to the `verify` phase so local `./mvnw verify` also enforces coverage |
| 🟠 Medium | Section 4.3 — Lombok | Banned annotations (`@val`, `@SneakyThrows`) are policy-only with no tooling enforcement | Add an ArchUnit test asserting banned Lombok annotations are never used in production code |
| 🟠 Medium | Section 6 — CI Pipeline | No `rules:` or `workflow:` block to prevent duplicate pipelines on MR + branch push events | Add a `workflow: rules:` block to prevent duplicate pipeline runs |

---

## ✅ Acceptance Criteria

### ⚙️ Repository Scaffold & Configuration Files

- [ ] **AC-01** — All scaffold files (`.editorconfig`, `lombok.config`, `.gitignore`, `hooks/pre-commit`, `.gitlab/merge_request_templates/Default.md`, `application.yml`) are committed to the repository root with documented content.
- [ ] **AC-02** — `.vscode/settings.json`, `extensions.json`, and `launch.json` are committed and a new team member opening the repo in VS Code is prompted to install all required extensions.
- [ ] **AC-03** — The `pre-commit` hook runs `./mvnw fmt:check checkstyle:check` and blocks commits that contain formatting or style violations.
- [ ] **AC-04** — All plugin versions used by the project are pinned in a `<properties>` block in `pom.xml` and enforced by `maven-enforcer-plugin requirePluginVersions`.

### 🎨 Code Formatting & Style Enforcement

- [ ] **AC-05** — `fmt-maven-plugin` is configured and `./mvnw fmt:check` fails the build on any unformatted Java file. Running `./mvnw fmt:format` auto-corrects all violations.
- [ ] **AC-06** — Checkstyle is configured with all rules defined in Section 3.3 (complexity, naming, imports, Javadoc, file length). `./mvnw checkstyle:check` fails on violations. Cyclomatic complexity threshold is set to **10**.
- [ ] **AC-07** — VS Code displays Checkstyle violations as inline warnings in real time using the `shengchen.vscode-checkstyle` extension pointing to the project's Checkstyle config file.

### 🔍 Static Analysis

- [ ] **AC-08** — SpotBugs is configured at Max effort / Medium threshold with exclusion filters for Lombok-generated code and Spring controller patterns. `./mvnw spotbugs:check` fails on detected bugs.
- [ ] **AC-09** — `lombok.config` bans `@val`, `@var`, and `@SneakyThrows`. An ArchUnit test (`ArchitectureTest.java`) asserts these annotations are absent from all production source files.
- [ ] **AC-10** — SonarLint extension is configured in `settings.json`. Connected mode settings are present (commented out) with instructions for binding to the SonarQube server.

### 🧪 Testing & Coverage

- [ ] **AC-11** — Surefire is configured to pick up `*Test.java` and `*Tests.java`. Failsafe is configured to pick up `*IT.java` and `*IntegrationTest.java`. Both run under `./mvnw verify`.
- [ ] **AC-12** — JaCoCo is bound to the `verify` phase and enforces ≥80% line coverage and ≥70% branch coverage. `./mvnw verify` fails locally if thresholds are not met.
- [ ] **AC-13** — JaCoCo excludes configuration classes, DTOs, entities, and `Application.java` from coverage calculation. Exclusion patterns are explicitly listed in `pom.xml`.
- [ ] **AC-14** — `ArchitectureTest.java` defines and passes at minimum: layered architecture rule (controller → service → repository), no circular dependencies, and no access to `*.impl` packages from outside.

### 🚀 CI/CD Pipeline

- [ ] **AC-15** — All 6 pipeline stages (`validate`, `build`, `test`, `analyze`, `package`, `deploy`) are defined in `.gitlab-ci.yml` with correct job dependencies and stage ordering.
- [ ] **AC-16** — Maven `~/.m2/repository` is cached in CI keyed on a hash of `pom.xml`. A pipeline on a clean runner completes within target time (under 8 minutes for validate → test stages).
- [ ] **AC-17** — A `workflow: rules:` block prevents duplicate pipeline runs when both an MR event and a branch push event occur simultaneously.
- [ ] **AC-18** — MR pipelines trigger `validate → build → test → analyze` with SonarQube MR decoration. Default branch push pipelines additionally trigger `package`. Tag pipelines additionally gate on manual approval for `production` deploy.

### 📊 SonarQube Integration

- [ ] **AC-19** — SonarQube project is created and bound. `SONAR_HOST_URL` and `SONAR_TOKEN` are configured as masked CI/CD variables. Setup steps are documented in the project README.
- [ ] **AC-20** — SonarQube receives all three report sources: JaCoCo XML coverage report, Checkstyle XML violations report, and SpotBugs XML bug report. The unified quality view reflects all three tool outputs.
- [ ] **AC-21** — After the initial pipeline run on the default branch, the SonarQube project quality gate reports **Passed**. The quality gate status is visible in the GitLab MR widget via SonarQube MR decoration.

---

## 🏁 Definition of Done

This issue is complete when:

1. A clean `./mvnw verify` passes on a freshly cloned repository with no additional setup.
2. A new MR triggers the full 6-stage pipeline without any manual variable configuration.
3. SonarQube reports the project quality gate as **Passed** 🟢.
