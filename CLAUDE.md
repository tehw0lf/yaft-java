# CLAUDE.md

Java port of YaFT (`de.tehwolf:yaft`). The reference implementation is
`TypeScript/yaft`; the normative rules are `yaft-conformance/SPEC.md`, and where
the two disagree the spec wins.

## Layout

- `src/main/java/de/tehwolf/yaft/`
  - `Evaluation` — the single definition of the time logic (R3–R13)
  - `Mapping` — backend response normalisation (R22–R25)
  - `YaFT` — `decorate`/`create` (class, evaluated once, R14) and `wrap`
    (method, evaluated per call, R15) over JDK dynamic proxies
  - `MethodToggles`, `EmptyShell`, `Nothing` — package-private proxy internals
- `src/test/java/de/tehwolf/yaft/conformance/` — adapter for the shared suite;
  an unknown case value must fail, never be skipped
- `conformance.lock` + `scripts/fetch-conformance.sh` — the suite is fetched
  into `src/test/conformance/` (gitignored) by the `fetchConformance` task

## Constraints

- No runtime dependencies. Jackson is a test dependency for reading cases only.
- Java 25 toolchain, no auto-provisioning. CI gets the JDK through the
  `java_version` input of `tehw0lf/workflows`.
- Compiler runs with `-Xlint:all -Werror`, javadoc with doclint `-Werror`.
- Gradle version bumps: `./gradlew wrapper --gradle-version X
  --gradle-distribution-sha256-sum <sum>` — always with the checksum.
- The version lives in `gradle.properties`; bump the patch on every PR.

## Pre-commit validation

```bash
./gradlew build
```

Exit code 0 required. Gradle may run on a newer JDK; the toolchain compiles
and tests with the installed JDK 25 either way.
