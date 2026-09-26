# CLAUDE.md

## Architecture

The current architecture is intentional. Extend it; don't restructure it or add new layers,
frameworks or libraries unless asked. `SPEC.md` is the source of truth: §3 describes the components,
§4 the tool contracts, §5 the platform APIs, §6 the business rules and error messages, §7 the
configuration and §8 the required tests.

### Layers (package `com.socialmcp`)

- `tools` — `SocialMcpTools` is the only MCP entry point (`@McpTool`). It validates and normalizes
  every argument (platform, handle, limit, enums, parts, images...), picks the service from the
  injected `List<SocialPlatformService>`, and delegates. Cross-platform logic (thread numbering,
  reply mention prefix, limit defaults/clamping, image checks) lives here, not in the services.
  `PlatformAwareToolDefinitions` rewrites the generated tool definitions to list only configured platforms.
- `platform` — `SocialPlatformService` is the Strategy interface; `mastodon.MastodonService` and
  `bluesky.BlueskyService` implement it. All platform-specific HTTP and JSON mapping stays inside
  its service. Shared helpers: `Json` (null-safe Jackson 3 tree access) and `ApiErrors` (generic
  upstream error translation).
- `model` — immutable records (and enums) shared by tools and services. Results are serialized to
  JSON by Spring AI; input records like `PollInput`/`ImageInput` define the tool's JSON schema.
  Internal-only records (`PublishedPost`, `ReplyTarget`, `QuoteTarget`, `PreparedImage`) are never returned.
- `media` — `ImageLoader` reads, sniffs and checks images; it never decodes pixels, re-encodes
  or resizes, and makes no platform calls. `Downloader` is the only URL download.
- `text` — stateless helpers (`HtmlText`, `TextLength`).
- `config` — `SocialProperties`, a `@ConfigurationProperties("social")` + `@Validated` record
  tree bound from `application.properties`.

### Rules

- **Adding a tool**: add the method to `SocialMcpTools`, the operation to `SocialPlatformService`,
  implement it in both services (Bluesky throws the spec's "doesn't support" message when the
  platform lacks a feature), add records to `model`, and update `FakePlatform` in the tests.
- **Adding a platform**: a new `platform.<name>` package with a `@Service` implementing
  `SocialPlatformService`. The tools must not need changes.
- Dispatch on action enums (`AccountAction`, `PostAction`, ...) with an exhaustive `switch`, no `default`,
  so a new constant fails compilation until every platform handles it.
- HTTP uses Spring `RestClient` (blocking) built from the injected `RestClient.Builder`. No WebClient,
  no other HTTP libraries. Constructors that tests need (a `Clock`, a `Sleeper`, ...) are package-private.
- Service beans are always registered; each checks `isConfigured()` at call time. Don't use
  `@ConditionalOnProperty` for platforms.
- Errors are thrown as `IllegalArgumentException` (bad input) or `IllegalStateException` (upstream
  failure) with the exact messages from SPEC §4/§6; Spring AI turns them into `isError` results.
  Never include credentials in messages. Tools call services through `call(service, ...)`, which
  wraps them in `ApiErrors.translate`; services only catch the upstream errors they map to a specific message.
- Every write tool calls `requireWritesEnabled(...)` first (the `social.posting-enabled` kill switch, SPEC §6.3).
- Null safety: every package has a `package-info.java` with `@NullMarked`; mark nullable types with
  JSpecify `@Nullable`. NullAway fails the build on violations in `src/main`. Required tool
  parameters are non-null but still null-checked at runtime (MCP input is untrusted).
- New settings go in `SocialProperties` (with defaults and validation) and in
  `application.properties` with an environment-variable placeholder and a comment.
- The server uses STDIO: never write to stdout (no `System.out`, no console logging). Log with an
  SLF4J `Logger` (`LoggerFactory.getLogger(...)`); logs go only to the log file.
- Tool and parameter descriptions (`@McpTool`/`@McpToolParam`) and the server instructions
  (`spring.ai.mcp.server.instructions`) are read by the model. Keep them in sync with SPEC §4; the
  instructions text must be identical in SPEC §4 and `application.properties`.
- Code must work on Linux and Windows (CI runs both): use `Path`/`Files`, no hard-coded path
  separators or line endings, including in tests.

### Workflow and tests

- For a new feature, update `SPEC.md` first (scope, §3 interface, §4 contract, §5 API mapping,
  §6 rules, §8 tests), then implement, then update `README.md` (the *Tools* table, *Configuration*
  table for new environment variables, *Limitations* and *Project layout* as needed).
- Tests mirror the main packages: `SocialMcpTools` against `FakePlatform` stubs, each service with
  `MockRestServiceServer` bound to a `RestClient.Builder` (no WireMock), AssertJ for assertions.
  Put shared test helpers in `TestSupport`, `TestProperties` and `TestImages`.
- Requires JDK 25. Build and test with `./mvnw verify` (`mvnw.cmd` on Windows cmd); run one test
  class with `./mvnw test -Dtest=MastodonServiceTest`. `.mvn/jvm.config` holds the javac flags Error
  Prone needs; don't remove them.
- Git: do feature work on a branch and merge through a pull request; `main` is built by CI
  (`.github/workflows/ci.yml`). Commit the spec change separately from the implementation
  (e.g. "Extend spec with image attachments", then "Implement image attachments"). Commit subjects
  are short, imperative and capitalized, with no prefix. Releases are cut by pushing a `v*` tag;
  only do that when asked.

## Code style

All Java code — production (`src/main/java`) and tests (`src/test/java`) alike — follows IntelliJ
IDEA's default formatter. Write new code so that running IntelliJ's *Reformat Code* (with
*Optimize imports* and *Rearrange code*) on it changes nothing. Use the existing files as the reference.

- **Indentation**: 4 spaces, never tabs. Continuation lines (wrapped arguments, chained
  builder calls, record components, `+` string concatenation) get 8 spaces.
- **Line length**: 120 characters.
- **Braces**: `} catch (...) {`, `} else {` and `} finally {` go on the same line as the closing brace.
- **Imports**, in this order, each group sorted alphabetically and separated by one blank line:
  1. all other imports (`com.socialmcp.*`, `org.*`, ...) together in one group
  2. `javax.*` and `java.*`
  3. static imports

  Use a wildcard (`import com.socialmcp.model.*;`) once 5 or more classes come from the same
  package; otherwise import each class. Static imports use a wildcard once 3 or more members come
  from the same class (`import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;`).
  No unused imports.
- **Javadoc**: always the multi-line form, even for a single sentence:
  ```java
  /**
   * The status, the {@code Location} header and the body.
   */
  ```
- **Member order** within a type: static fields, instance fields, constructors, static methods,
  instance methods, then nested types (interfaces, records, enums, classes) at the end.
- Keep the existing blank line after a type's opening brace and before its closing brace.

Don't reformat code you aren't otherwise changing; keep diffs focused on the feature.
