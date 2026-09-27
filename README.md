# YaFT for Java

<div align="center">
  <img src="./logo.svg" alt="YaFT Logo" width="140">
</div>

---

Feature toggles for classes and methods in Java, following the same rules as
[`@tehw0lf/yaft`](https://github.com/tehw0lf/yaft-ts) and every other YaFT port.
It passes all cases of
[yaft-conformance](https://github.com/tehw0lf/yaft-conformance) and has no
runtime dependencies.

Requires Java 25.

---

## Installation

From Maven Central:

```kotlin
implementation("de.tehwolf:yaft:0.2.4")
```

```xml
<dependency>
  <groupId>de.tehwolf</groupId>
  <artifactId>yaft</artifactId>
  <version>0.2.4</version>
</dependency>
```

Releases are signed with the key `2A0351C28EB122B8946E52E39A12B17723327580`.
Fetch it from keyserver.ubuntu.com to verify; the keys.openpgp.org copy carries
no user ID, which gpg refuses to import.

## Initialization

Set a `FeatureProvider` once, at startup:

```java
import de.tehwolf.yaft.Feature;
import de.tehwolf.yaft.LocalFeatureProvider;
import de.tehwolf.yaft.YaFT;

YaFT.setProvider(new LocalFeatureProvider(Map.of(
        "new-algorithm", new Feature("new-algorithm", "true"))));
```

Three providers ship with the library:

| Provider | Data | Time bounds |
|---|---|---|
| `LocalFeatureProvider` | full `Feature` records | yes — `activeAt`, `disabledAt` |
| `LocalBooleanProvider` | `{"myToggle": true}` | none, by design |
| `ApiFeatureProvider` | a group from the YaFT backend | yes, evaluated locally |

The local ones take their data from a `Map`, or from any parsed JSON through
`fromResponse(...)`. The core reads the plain `Map`/`List`/`String` tree that
Jackson, Gson and friends all produce, so it does not pick a JSON library for
you. `LocalFeatureProvider.fromResponse` accepts every envelope and field
spelling the Go backend has ever sent.

### From a YaFT backend

`ApiFeatureProvider` loads a toggle group from the Go backend. It does not
parse JSON itself: you hand it a decoder, normally your application's own
Jackson or Gson instance, so the library adds no dependency that could clash
with yours and contains no hand-written parser.

```java
ObjectMapper mapper = new ObjectMapper();
ApiFeatureProvider provider = ApiFeatureProvider
        .builder(URI.create("https://yaft.tehwolf.de"), groupUuid,
                 body -> mapper.readValue(body, Object.class))
        .build();

provider.refresh();          // throws if the backend cannot be read
YaFT.setProvider(provider);

scheduler.scheduleWithFixedDelay(provider::refreshQuietly, 30, 30, TimeUnit.SECONDS);
```

- Toggles are looked up by name within the group: `isEnabled("newCheckout")`
  and `@FeatureToggle(key = "newCheckout")` both find `<uuid>|newCheckout`.
  An annotation value has to be a compile-time constant, so it could never
  contain the group's UUID. The full key works too.
- `refresh()` asks `/collectionHash/{uuid}` first and fetches the group only
  when it changed.
- Time bounds are evaluated locally against the clock, so a scheduled toggle
  flips at its exact instant, not when the backend's cron job runs.
- A failed refresh throws (`refreshQuietly()` logs instead) and **keeps the
  previous data**: a backend outage does not switch everything off. So does a
  `200` whose body is not a toggle group, such as a proxy's error page. Before the
  first successful refresh every feature is off.
- The group UUID is checked strictly before it goes into the URL; only `http`
  and `https` are accepted, redirects are not followed, requests time out after
  5 seconds and bodies over 1 MiB are rejected. `timeout`, `maxBodyBytes`,
  `client` and `clock` on the builder change these.

Reading needs no secret. Writing toggles is not part of the library.

### Your own provider

`FeatureProvider` is a single-method interface, so anything that can answer
`isEnabled(key)` is a provider:

```java
YaFT.setProvider(key -> System.getenv("FEATURE_" + key) != null);
```

A feature is on only when `value` is exactly `"true"` and the current time is
inside `[activeAt, disabledAt)`. Bounds must be RFC 3339 with an offset
(`2026-09-18T15:00:00Z`); a bare date or a timestamp without an offset is
ignored with a warning, never guessed at. The clock is injectable:

```java
new LocalFeatureProvider(features, InstantSource.fixed(Instant.parse("2026-09-18T12:00:00Z")));
```

## Usage

An annotation does nothing on its own in Java, so toggled code is reached
through `YaFT`, by interface.

### Classes

```java
@FeatureToggle(key = "new-algorithm", fallback = OldAlgorithm.class)
class NewAlgorithm implements Algorithm { ... }

class OldAlgorithm implements Algorithm { ... }

Algorithm algorithm = YaFT.create(Algorithm.class, NewAlgorithm.class);
```

| Toggle | `fallback` set | Result |
|---|---|---|
| on | either | `NewAlgorithm` |
| off | yes | `OldAlgorithm` |
| off | no | an empty shell: every method returns nothing |

`YaFT.decorate(Algorithm.class, NewAlgorithm.class)` returns a
`Supplier<Algorithm>` instead, for creating many instances. Both classes need
a no-argument constructor.

### Methods

```java
class Service implements Processing {
    @FeatureToggle(key = "enhanced-processing", fallbackMethod = "basic")
    public String process(String input) { ... }

    private String basic(String input) { ... }
}

Processing processing = YaFT.wrap(Processing.class, new Service());
```

Off with a `fallbackMethod`: the fallback runs with the same arguments on the
same instance, so it sees the same fields. It must take the same parameter
types and return a compatible type. Off without one: the call returns
nothing.

A toggle may also be declared on the interface method itself.

### When a toggle is read

This is fixed by the spec, identical in every port, and worth knowing:

| Target | Evaluated |
|---|---|
| Class | **once**, in `create`/`decorate` |
| Method | on **every call** |

A class toggle switched after `decorate` does not affect its supplier; call
`decorate` again to decide again. A method toggle takes effect on the next
call.

### "Nothing"

| Return type | Disabled result |
|---|---|
| reference type | `null` |
| primitive | its zero value (`0`, `false`, `'\0'`) |
| `CompletableFuture`, `CompletionStage`, `Future` | already completed with `null` |
| `Optional`, `OptionalInt`, `OptionalLong`, `OptionalDouble` | empty |

A disabled async method therefore never breaks a `join()` at the call site.

### Fail fast

Every entry point throws when no provider is set
(`IllegalStateException: FeatureToggleProvider not set`), and a misconfigured
annotation — a fallback method that does not exist, a fallback class with the
wrong type or no constructor, a toggle on a method the interface cannot reach —
is rejected with an `IllegalArgumentException` when you decorate, not when
the toggle first goes off in production.

### Spring and other proxies

Wrap the object **before** a framework proxies it, and let the framework
advise the YaFT proxy:

```java
@Bean
Processing processing() {
    return YaFT.wrap(Processing.class, new Service());   // Spring may advise this
}
```

The other way round cannot work: a Spring (CGLIB or JDK) proxy's class carries
none of your annotations, and a fallback method would run on the proxy
instance, whose fields are empty. `YaFT.wrap` therefore refuses a JDK proxy,
and any object whose toggled method is overridden without the annotation,
instead of silently ignoring the toggle. Both directions are exercised against
real Spring proxies in [yaft-java-playground](https://github.com/tehw0lf/yaft-java-playground).

### Modules

On the class path nothing is needed. In a named module, open the packages
holding toggled classes to `de.tehwolf.yaft`, so it can reach non-public
constructors and fallback methods.

## Conformance

`conformance.lock` pins a release of the suite by version and checksum.
`./gradlew test` fetches and verifies it before every run, then runs all its
cases next to this library's own tests. A suite bump is a one-line diff to
that file.

## Development

```bash
./gradlew build    # compile (-Werror), conformance + unit tests, javadoc
```

## License

MIT
