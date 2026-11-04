# 0012. Lombok for constructor injection and entity getters

Status: Accepted.

## Context

The backend uses records for DTOs, requests and responses, so they carry no boilerplate.
The remaining boilerplate was mechanical: Spring beans with constructors that only assign `final` fields (43 classes) and JPA entities with trivial getters (about 80 methods), roughly 650 of 9 000 lines.
Entities are deliberately not data bags: state changes go through domain methods such as `attachWorktree` or `recordUsage`, and constructors generate ids and set defaults.

## Decision

- The backend uses Lombok, version managed by Spring Boot, as `compileOnly` and `annotationProcessor`.
- Allowed annotations:
  - `@RequiredArgsConstructor` on Spring beans whose constructor only assigns `final` fields;
  - `@Getter` on entities, on the class or on single fields, with `AccessLevel` to keep package-private getters package-private.
- Not allowed: `@Data`, `@Setter`, `@Builder`, `@EqualsAndHashCode`, `@ToString`, `@Value`, `@SneakyThrows`, and `@AllArgsConstructor`/`@NoArgsConstructor` on entities.
- Entity constructors and domain methods stay hand-written. Hand-written getters with a different shape (for example returning `Optional`) take precedence; Lombok does not generate a getter that already exists.
- `lombok.config` in `backend/` marks generated code with `@lombok.Generated` and copies `@Qualifier` and `@Value` from fields to generated constructors.

## Consequences

- About 575 lines of boilerplate are gone; adding a dependency to a service is a one-line change.
- Getter-heavy entities may now expose read-only getters for a few fields that had none, such as `createdAt`. Setters are still absent.
- The build depends on Lombok supporting the JDK in use. Lombok 1.18.48 supports Java 25; a JDK upgrade may have to wait for a Lombok release.
- IDEs need Lombok support (built into IntelliJ IDEA, an extension in VS Code).

## Rejected alternatives

- **No Lombok.** Keeps the build free of annotation processing, but leaves boilerplate that records cannot remove.
- **Full Lombok including `@Data` and `@Builder` on entities.** Breaks the domain-method style and causes known JPA problems: `equals`/`hashCode` over mutable ids, `toString` touching lazy associations.
