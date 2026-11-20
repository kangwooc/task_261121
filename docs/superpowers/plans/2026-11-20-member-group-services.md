# Member / Group Services Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 물리적으로 분리된 `member-service`(외부 공개)와 `group-service`(내부 전용)를 Kotlin/Spring Boot 멀티모듈로 구현한다. 두 서비스는 `*-api` 계약 모듈로만 연결되고, 그룹 장애 시 3초 안에 원인이 드러나는 503을 반환한다.

**Architecture:** 4개 Gradle 모듈(`group-api`, `group-service`, `member-api`, `member-service`)로 구성한다. 서비스는 `@HttpExchange` 계약 인터페이스를 컨트롤러로 구현하고, member-service는 같은 인터페이스로 HTTP Service Client 프록시를 만든다. 조회 전용 CQRS(Query → Handler → View)는 JPA DTO projection으로 구현한다. 그룹 호출은 `Semaphore → RetryTemplate → CircuitBreaker → HTTP` 순서를 코드로 명시해 조합한다.

**Tech Stack:** JDK 25, Kotlin 2.3.21, Spring Boot 4.1.3 (Framework 7.0.11, Hibernate 7.4.15), Gradle 9.3.1, PostgreSQL 17, Flyway, Spring Data JPA, Resilience4j 2.4.0 (core만), Micrometer Tracing(Brave) + Prometheus, springdoc-openapi 3.2.0, Testcontainers 2.0.5, OkHttp MockWebServer3 5.5.1

**Spec:** `docs/superpowers/specs/2026-11-20-member-group-services-design.md`. 모든 task는 spec을 함께 읽는다. 이 계획에 없는 세부 사항은 spec이 기준이다.

## Global Constraints

- JDK 25 toolchain, Kotlin 2.3.21, Spring Boot 4.1.3, Gradle wrapper 9.3.1. Boot가 관리하는 의존성 버전은 덮어쓰지 않는다.
- Kotlin 컴파일러 옵션: `-Xannotation-default-target=param-property`, `-Xjsr305=strict`. 플러그인: `kotlin("plugin.spring")`, `kotlin("plugin.jpa")`(서비스 모듈). all-open 대상: `jakarta.persistence.Entity`, `MappedSuperclass`, `Embeddable`.
- Starter: `spring-boot-starter-webmvc`, `spring-boot-starter-restclient`, `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, 테스트용 `spring-boot-starter-webmvc-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`. `spring-boot-docker-compose`는 쓰지 않는다.
- Jackson 3: 코드 import는 `tools.jackson.*`, 어노테이션은 `com.fasterxml.jackson.annotation.*`. Kotlin 모듈은 `tools.jackson.module:jackson-module-kotlin`.
- 패키지 루트: `dev.task.group`, `dev.task.group.api`, `dev.task.member`, `dev.task.member.api`. [계획 단계 판단: spec §4의 `<base>`를 중립 이름으로 확정]
- `*-service` 모듈끼리는 어떤 scope로도 의존하지 않는다. `*-api` 모듈은 Spring Boot에 의존하지 않는다(`spring-web`, `jackson-annotations`만).
- 포트: member 8080(외부), group 8081(`server.address=127.0.0.1`), 관리 포트 9080/9081(`management.server.address=127.0.0.1`), DB 15432/15433.
- 날짜 JSON 형식은 `"yyyy-MM-dd HH:mm"`이다. DB는 `timestamptz`이고, 렌더링 zone은 `app.time-zone`(기본 `Asia/Seoul`)이다.
- ID 형식은 정규식 `^[a-z0-9]{10}$`이다.
- 모든 오류는 `ProblemDetail`(`application/problem+json`)이고 확장 프로퍼티 `code`, `traceId`를 포함한다.
- 저장소 어디에도 회사명 리터럴을 쓰지 않는다(패턴은 escape 표기). 커밋 메시지 끝에는 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.
- 명령은 루트에서 실행하고, 테스트 명령은 `./gradlew :<module>:test --tests '<FQN>'` 형식이다.

## Review Focus

1. **빈 결과 페이지**(멤버 0명, 마지막 페이지 뒤의 `page`, `hasNext=false` 이후 요청)에서는 200과 `members: []`를 주고 그룹 서비스는 호출하지 않는다. 503이나 400이 아니다. → Task 6 테스트 `emptyPageReturns200WithoutCallingGroupService`
2. **ID 형식이 잘못된 멤버 상세 요청**(`/api/member/ABC`, 11자, 특수문자)은 DB나 그룹 서비스를 호출하기 전에 400을 준다. 404가 아니다. → Task 6 `invalidMemberIdIs400WithoutAnyCall`
3. **형식은 맞지만 내용이 깨진 cursor**(base64는 유효하지만 JSON 필드가 없거나 타입이 틀리거나 방향이 다름)는 500이 아니라 400이다. → Task 4 `CursorCodecTest.rejectsStructurallyInvalidPayloads`
4. **한글/영문/대소문자가 섞인 이름**도 offset과 cursor가 같은 순서를 낸다. 둘 다 DB collation 기준이기 때문이다. → Task 4 시나리오 #1 데이터셋에 포함
5. **summary 요청의 중복 ID**(`memberIds=a,a,b`)는 한 번만 집계한다. 그룹 서비스가 요청하지 않은 ID를 돌려줘도 member-service는 무시한다. → Task 3 `duplicateIdsAreCountedOnce`, Task 5 `unrequestedMembersInSummaryAreIgnored`

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle/wrapper/*, gradlew*, .editorconfig, compose.yaml
scripts/smoke.sh, scripts/check-forbidden.sh
group-api/src/main/kotlin/dev/task/group/api/
  GroupApi.kt                      @HttpExchange 계약
  GroupDtos.kt                     GroupSummaryResponse, GroupSummary, GroupMemberPermission, MemberGroupDetailResponse
  Enums.kt                         Pricing, Permission
member-api/src/main/kotlin/dev/task/member/api/
  MemberApi.kt, MemberDtos.kt      MemberListResponse, MemberSummary, PageInfo, CursorInfo, MemberDetailResponse, MemberGroupInfo
group-service/src/main/kotlin/dev/task/group/
  GroupServiceApplication.kt
  web/GroupController.kt, web/GroupExceptionHandler.kt
  query/GetGroupSummaries.kt       Query + Handler + View (한 파일)
  query/GetMemberGroupDetail.kt
  infrastructure/persistence/GroupEntity.kt, GroupMemberEntity.kt, GroupMemberJpaRepository.kt, GroupReadRepository.kt
group-service/src/main/resources/application.yml, db/migration/V1__schema.sql, db/seed/V100__seed.sql
member-service/src/main/kotlin/dev/task/member/
  MemberServiceApplication.kt
  web/MemberController.kt, web/MemberExceptionHandler.kt, web/ProblemDetails.kt
  query/GetMembers.kt, query/GetMemberDetail.kt, query/Pagination.kt, query/port/GroupReader.kt
  infrastructure/persistence/MemberEntity.kt, MemberJpaRepository.kt, MemberReadRepository.kt, CursorCodec.kt
  infrastructure/group/GroupClientConfig.kt, GroupClientProperties.kt, GroupServiceGateway.kt, GroupCallStage.kt, GroupServiceException.kt
member-service/src/main/resources/application.yml, application-prod.yml, db/migration/V1__schema.sql, db/seed/V100__seed.sql
```

---

### Task 0: 저장소와 빌드 골격

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.editorconfig`, `compose.yaml`, 각 모듈의 빈 `build.gradle.kts`, `scripts/check-forbidden.sh`
- Modify: `.gitignore`(`ai-logs/`는 **제외하지 않는다**)

**Interfaces:**
- Produces: 모듈 4개, `libs` 버전 카탈로그 alias(`spring-boot`, `kotlin-jvm`, `kotlin-spring`, `kotlin-jpa`, `resilience4j-circuitbreaker`, `resilience4j-micrometer`, `springdoc-webmvc-ui`, `mockwebserver3`, `mockwebserver3-junit5`)

- [ ] **Step 1:** `git init -b main`. `gradle wrapper --gradle-version 9.3.1`로 wrapper를 만든다. 로컬에 gradle이 없으면 `brew install gradle`을 쓰는데, 설치 전에 사용자에게 확인한다.
- [ ] **Step 2:** `settings.gradle.kts`에 4개 모듈을 include하고 foojay toolchain resolver 플러그인을 넣는다. 루트 `build.gradle.kts`의 `subprojects`에 Kotlin JVM, `jvmToolchain(25)`, Global Constraints의 컴파일러 옵션, JUnit Platform을 공통 적용한다. 서비스 모듈에만 `org.springframework.boot`, `kotlin("plugin.spring")`, `kotlin("plugin.jpa")`, Spring dependency management(BOM `spring-boot-dependencies:4.1.3`)를 적용한다. `*-api` 모듈은 BOM `platform(...)`만으로 `spring-web`, `jackson-annotations` 버전을 받는다.
- [ ] **Step 3:** `compose.yaml`에 `member-db`(postgres:17, `15432:5432`, db/user/pw `member`)와 `group-db`(`15433:5432`, `group`)를 두고, 각각 `pg_isready` healthcheck를 단다.
- [ ] **Step 4:** `scripts/check-forbidden.sh`를 만든다. 저장소 전체(`.git`, `build`, `.gradle`, `.playwright-mcp` 제외)를 `perl -CSD`로 검사하고, 패턴은 `.claude/hooks/save-transcript.sh`와 같은 escape 표기를 쓴다. 발견하면 파일:줄을 출력하고 exit 1을 낸다.
- [ ] **Step 5:** 실행: `./gradlew build && scripts/check-forbidden.sh`. Expected: `BUILD SUCCESSFUL`, 검사 exit 0.
- [ ] **Step 6:** 커밋: `chore: multi-module gradle skeleton, compose, AI session logging`. 기존 `.claude/`, `README.md`, `docs/`, `ai-logs/`를 포함한다.

---

### Task 1: 계약 모듈 (`group-api`, `member-api`)

**Files:**
- Create: `group-api/.../GroupApi.kt`, `GroupDtos.kt`, `Enums.kt`, `member-api/.../MemberApi.kt`, `MemberDtos.kt`
- Test: `group-api/src/test/kotlin/dev/task/group/api/GroupDtosJsonTest.kt`, `member-api/src/test/kotlin/dev/task/member/api/MemberDtosJsonTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class Pricing { Free, Advanced, Premium }
  enum class Permission { Viewer, Editor, Admin }

  @HttpExchange("/api")
  interface GroupApi {
      @GetExchange("/groups/summary") fun getGroupSummaries(@RequestParam memberIds: List<String>): GroupSummaryResponse
      @GetExchange("/group") fun getMemberGroup(@RequestParam memberId: String): MemberGroupDetailResponse
  }
  data class GroupSummaryResponse(val groups: List<GroupSummary>)
  data class GroupSummary(val groupId: String, val groupName: String, val totalMemberCount: Int, val members: List<GroupMemberPermission>)
  data class GroupMemberPermission(val memberId: String, val permission: Permission)
  data class MemberGroupDetailResponse(val memberId: String, val groupId: String, val groupName: String,
      @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm") val registeredDate: LocalDateTime, val pricing: Pricing, val permission: Permission)

  @HttpExchange("/api")
  interface MemberApi {
      @GetExchange("/members") fun getMembers(
          @RequestParam(required = false) size: Int?, @RequestParam(required = false) sort: String?,
          @RequestParam(required = false) pagination: String?, @RequestParam(required = false) page: Int?,
          @RequestParam(required = false) cursor: String?): MemberListResponse
      @GetExchange("/member/{memberId}") fun getMember(@PathVariable memberId: String): MemberDetailResponse
  }
  data class MemberListResponse(val members: List<MemberSummary>, val page: PageInfo? = null, val cursor: CursorInfo? = null)  // null 필드는 직렬화하지 않음 (@JsonInclude(NON_NULL) — 클래스 레벨)
  data class MemberSummary(val id: String, val name: String, val group: String?, val permission: Permission?)  // 여기 null은 반드시 직렬화 (필드 레벨 ALWAYS)
  data class PageInfo(val number: Int, val size: Int, val totalElements: Long, val totalPages: Int)
  data class CursorInfo(val next: String?, val hasNext: Boolean)
  data class MemberDetailResponse(val id: String, val name: String, val email: String, val address: String,
      @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm") val registeredDate: LocalDateTime, val group: MemberGroupInfo?)  // group null은 직렬화
  data class MemberGroupInfo(val id: String, val name: String, @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm") val registeredDate: LocalDateTime,
      val pricing: Pricing, val permission: Permission)
  ```
  `memberIds`는 쉼표로 구분한 query string으로 전송된다(Spring 기본 동작).

- [ ] **Step 1: 실패하는 테스트 작성.** `GroupDtosJsonTest.serializesExactlyLikeSpec()`: spec §5.2의 예시 값으로 DTO를 만들고, Jackson 3 `JsonMapper`(kotlin module)의 직렬화 결과가 spec 예시 JSON과 JSON 단위로 같은지 검사한다. `MemberDtosJsonTest`는 세 가지를 검사한다.
  - `listOmitsUnusedPagingBlock()`: `page`만 있으면 `cursor` 키가 없다.
  - `nullGroupIsSerializedAsNull()`: `"group": null`, `"permission": null` 키가 존재한다.
  - `detailMatchesSpec()`
- [ ] **Step 2:** 실행 `./gradlew :group-api:test :member-api:test`. Expected: 컴파일 실패.
- [ ] **Step 3:** 위 Interfaces 그대로 구현한다.
- [ ] **Step 4:** 같은 명령. Expected: PASS.
- [ ] **Step 5:** 커밋 `feat(api): group/member contract modules`

---

### Task 2: group-service 저장소 (스키마, 시드, JPA 조회)

**Files:**
- Create: `group-service/src/main/resources/application.yml`, `db/migration/V1__schema.sql`, `db/seed/V100__seed.sql`, `GroupServiceApplication.kt`, `infrastructure/persistence/*`
- Test: `group-service/src/test/kotlin/dev/task/group/infrastructure/persistence/GroupReadRepositoryTest.kt`, `group-service/src/test/kotlin/dev/task/group/TestcontainersConfig.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class GroupMembershipView(val memberId: String, val groupId: String, val groupName: String, val permission: Permission)
  data class GroupCountView(val groupId: String, val totalMemberCount: Long)
  data class MemberGroupDetailView(val memberId: String, val groupId: String, val groupName: String,
                                   val joinedAt: Instant, val pricing: Pricing, val permission: Permission)
  class GroupReadRepository {
      @Transactional(readOnly = true) fun findMemberships(memberIds: Collection<String>): List<GroupMembershipView>
      @Transactional(readOnly = true) fun countMembers(groupIds: Collection<String>): List<GroupCountView>
      @Transactional(readOnly = true) fun findMemberGroup(memberId: String): MemberGroupDetailView?
  }
  @TestConfiguration class TestcontainersConfig { @Bean @ServiceConnection fun postgres() = PostgreSQLContainer("postgres:17") }
  ```

- [ ] **Step 1:** `V1__schema.sql`은 spec §6의 `groups`, `group_members` DDL을 그대로 쓴다. CHECK 제약 값은 `'Free','Advanced','Premium'`, `'Viewer','Editor','Admin'`이다. `V100__seed.sql`에는 그룹 5개(pricing 3종을 모두 포함)를 넣고, 멤버 45명을 배정한다. 멤버 ID는 Task 4 시드와 공유하는 목록을 쓰고, **나머지 5명은 그룹에 배정하지 않는다**. 이 ID 목록을 member 시드와 맞추는 것이 이 task의 책임이다. ID 50개는 `^[a-z0-9]{10}$`을 만족하는 고정 문자열이다.
- [ ] **Step 2:** `application.yml`에 다음을 둔다.
  - `server.port=8081`, `server.address=127.0.0.1`
  - datasource `jdbc:postgresql://localhost:15433/group`
  - `spring.jpa.open-in-view=false`, `ddl-auto=validate`
  - `spring.flyway.locations=classpath:db/migration,classpath:db/seed`
  - `spring.threads.virtual.enabled=true`
- [ ] **Step 3: 실패하는 테스트 작성** (`@DataJpaTest` + `@Import(TestcontainersConfig::class, GroupReadRepository::class)` + `@AutoConfigureTestDatabase(replace = NONE)`, 시드 사용):
  - `findMembershipsReturnsOnlyGroupedMembers()`: 그룹이 있는 ID 2개와 없는 ID 1개를 넣으면 결과는 2건이고 permission이 시드와 일치한다.
  - `countMembersReturnsWholeGroupSize()`: 시드 그룹의 전체 인원 수와 일치한다.
  - `findMemberGroupReturnsNullForUngrouped()`
- [ ] **Step 4:** 실행 `./gradlew :group-service:test --tests '*GroupReadRepositoryTest'`. Expected: FAIL.
- [ ] **Step 5:** 엔티티와 `GroupMemberJpaRepository`를 구현한다. 조회는 JPQL constructor expression(`select new dev.task.group...View(...)`)으로 하고 엔티티를 반환하지 않는다. `GroupReadRepository`가 `@Transactional(readOnly = true)` 경계다.
- [ ] **Step 6:** Step 4를 다시 실행한다. Expected: PASS.
- [ ] **Step 7:** 커밋 `feat(group): schema, seed and read repository`

---

### Task 3: group-service 조회 핸들러와 웹 계층

**Files:**
- Create: `query/GetGroupSummaries.kt`, `query/GetMemberGroupDetail.kt`, `web/GroupController.kt`, `web/GroupExceptionHandler.kt`
- Test: `group-service/src/test/kotlin/dev/task/group/web/GroupApiTest.kt`

**Interfaces:**
- Consumes: Task 1 `GroupApi`과 DTO, Task 2 `GroupReadRepository`
- Produces: 실행 중인 group-service의 `GET /api/groups/summary`, `GET /api/group`. 404 응답은 `code=MEMBER_GROUP_NOT_FOUND`, 400 응답은 `code=INVALID_REQUEST`

- [ ] **Step 1: 실패하는 테스트 작성** (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + TestcontainersConfig). **클라이언트는 `GroupApi` 프록시**(`HttpServiceProxyFactory` + `RestClient`, base-url = 로컬 포트)로 만든다. 이것이 시나리오 #9 계약 일치 검증이다.
  - `summaryMatchesSpecShape()`: 응답 그룹의 `members`에는 요청한 ID만 있고, `totalMemberCount`는 그룹 전체 인원이다.
  - `duplicateIdsAreCountedOnce()`: `[a,a,b]`와 `[a,b]`의 결과가 같다.
  - `detailOfUngroupedMemberIs404WithCode()`: `HttpClientErrorException.NotFound`가 나고, 본문 `code == "MEMBER_GROUP_NOT_FOUND"`, `traceId` 키가 있다.
  - `invalidSummaryRequestsAre400()`: 파라미터화한다. 빈 목록, ID 101개, `ABC`, 11자 ID가 모두 400이고 `code == "INVALID_REQUEST"`다.
  - `controllerImplementsContract()`: `GroupApi::class.java.isAssignableFrom(GroupController::class.java)`
- [ ] **Step 2:** 실행 `./gradlew :group-service:test --tests '*GroupApiTest'`. Expected: FAIL.
- [ ] **Step 3:** Handler를 구현한다.
  - `GetGroupSummariesHandler.handle(GetGroupSummariesQuery(memberIds: Set<String>)): GroupSummaryResponse`는 memberships와 counts를 조합한다.
  - `GetMemberGroupDetailHandler.handle(memberId): MemberGroupDetailResponse?`는 `joinedAt`을 `app.time-zone`의 `LocalDateTime`으로 변환한다.
  - Query 생성 시점에 검증한다(1..100개, 정규식). 위반하면 `InvalidRequestException(message)`를 던진다.
- [ ] **Step 4:** `GroupController : GroupApi`를 구현한다. 그룹이 없으면 `MemberGroupNotFoundException`을 던진다. `GroupExceptionHandler`(`@RestControllerAdvice`)가 400과 404를 ProblemDetail로 만들고 `code`, `traceId`를 넣는다. 이 task에서는 `traceId`에 `Tracer`가 없으면 `null`을 넣고, Task 7에서 채운다.
- [ ] **Step 5:** Step 2를 다시 실행한다. Expected: PASS.
- [ ] **Step 6:** 커밋 `feat(group): summary/detail query handlers and contract controller`

---

### Task 4: member-service 저장소 (스키마, 시드, offset/keyset, cursor)

**Files:**
- Create: `member-service/src/main/resources/application.yml`, `db/migration/V1__schema.sql`, `db/seed/V100__seed.sql`, `MemberServiceApplication.kt`, `infrastructure/persistence/MemberEntity.kt`, `MemberJpaRepository.kt`, `MemberReadRepository.kt`, `CursorCodec.kt`, `query/Pagination.kt`
- Test: `.../persistence/MemberPagingTest.kt`, `.../persistence/CursorCodecTest.kt`, `member-service/src/test/kotlin/dev/task/member/TestcontainersConfig.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class SortDirection { ASC, DESC }
  sealed interface Pagination {
      data class Offset(val page: Int) : Pagination
      data class Cursor(val after: CursorKey?) : Pagination   // null = 첫 페이지
  }
  data class CursorKey(val name: String, val registeredAt: Instant, val id: String, val direction: SortDirection)
  object CursorCodec { fun encode(key: CursorKey): String; fun decode(raw: String): CursorKey /* 실패 시 InvalidRequestException */ }
  data class MemberRow(val id: String, val name: String, val email: String, val address: String, val registeredAt: Instant)
  data class MemberSlice(val rows: List<MemberRow>, val page: PageMeta?, val nextCursor: CursorKey?, val hasNext: Boolean)
  data class PageMeta(val number: Int, val size: Int, val totalElements: Long, val totalPages: Int)
  class MemberReadRepository {
      @Transactional(readOnly = true) fun findPage(size: Int, direction: SortDirection, pagination: Pagination): MemberSlice
      @Transactional(readOnly = true) fun findById(id: String): MemberRow?
  }
  class InvalidRequestException(message: String) : RuntimeException(message)   // member 쪽, web에서 400 매핑
  ```

- [ ] **Step 1:** `V1__schema.sql`은 spec §6의 `members`와 `members_name_sort_idx`를 쓴다. `V100__seed.sql`에는 Task 2와 같은 50개 ID를 쓴다. 반드시 포함할 데이터:
  - (a) 같은 이름 + 같은 `registered_at`인 멤버 3명
  - (b) 같은 이름 + 다른 시각인 멤버 2명
  - (c) 한글 이름, 영문 대문자/소문자로 시작하는 이름
  - 시각은 µs 단위까지 다르게 넣은 값을 1쌍 포함한다.
- [ ] **Step 2:** `application.yml`은 Task 2와 같은 구성에 `server.port=8080`, datasource `localhost:15432/member`를 쓰고, `server.address`는 지정하지 않는다.
- [ ] **Step 3: 실패하는 테스트 작성.**
  - `CursorCodecTest.roundTripsWithMicrosecondPrecision()`
  - `CursorCodecTest.rejectsStructurallyInvalidPayloads()`: 파라미터화한다. base64가 아닌 문자열, 유효한 base64지만 JSON이 아닌 값, 필드 누락, `registeredAt`이 문자열인 경우가 모두 `InvalidRequestException`이다.
  - `MemberPagingTest`(Testcontainers + 시드, 각 방향 ASC/DESC × size {1, 3, 7}):
    - `offsetTraversalVisitsEveryMemberOnceInOrder()`: 모든 페이지를 이어 붙이면 ID 집합이 50개이고 중복이 없다. 순서는 `(name, registeredAt, id)`에 방향을 적용한 것이고, 기대값은 DB에 `ORDER BY name, registered_at, id`로 직접 질의해서 만든다(collation 일치).
    - `cursorTraversalVisitsEveryMemberOnceInOrder()`: 같은 검증을 `nextCursor`가 없어질 때까지 반복한다.
    - `offsetAndCursorProduceIdenticalOrder()`
    - `lastPageHasNoNext()`, `pageBeyondEndIsEmpty()`
- [ ] **Step 4:** 실행 `./gradlew :member-service:test --tests '*persistence*'`. Expected: FAIL.
- [ ] **Step 5:** 구현한다.
  - offset: `MemberJpaRepository.findAllProjectedBy(pageable: Pageable): Page<MemberRow>`. 정렬은 `Sort.by(dir, "name", "registeredAt", "id")`이다.
  - keyset: `@Query`를 두 개(ASC/DESC) 쓴다. 아래 HQL에 `Limit.of(size + 1)`을 적용하고, `size + 1`번째 행이 있으면 `hasNext=true`, `nextCursor`는 `size`번째 행으로 정한다.
    ```
    select new dev.task.member.infrastructure.persistence.MemberRow(m.id, m.name, m.email, m.address, m.registeredAt)
    from MemberEntity m
    where (m.name, m.registeredAt, m.id) > (:name, :registeredAt, :id)      -- DESC는 <
    order by m.name asc, m.registeredAt asc, m.id asc                         -- DESC는 desc
    ```
    cursor가 null인 첫 페이지는 where 없는 같은 쿼리(`findFirst...`)를 쓴다.
  - **Fallback**: Hibernate가 튜플 비교를 거부하면(쿼리 파싱 예외) Spring Data `Window<MemberRow>` + `ScrollPosition.keyset()`으로 바꾸고, 그 사실을 README "해석/판단" 절에 적는다.
  - `CursorCodec`은 base64url(no padding) JSON을 쓴다: `{"n":name,"r":epochMicros,"i":id,"d":"ASC"}`.
- [ ] **Step 6:** Step 4를 다시 실행한다. Expected: PASS.
- [ ] **Step 7:** 커밋 `feat(member): schema, seed, offset and keyset paging`

---

### Task 5: 그룹 서비스 호출 게이트웨이 (탄력성 조합)

**Files:**
- Create: `query/port/GroupReader.kt`, `infrastructure/group/GroupClientConfig.kt`, `GroupClientProperties.kt`, `GroupServiceGateway.kt`, `GroupCallStage.kt`, `GroupServiceException.kt`
- Modify: `member-service/build.gradle.kts`(resilience4j-circuitbreaker, resilience4j-micrometer, `testImplementation` mockwebserver3), `application.yml`
- Test: `member-service/src/test/kotlin/dev/task/member/infrastructure/group/GroupServiceGatewayTest.kt`

**Interfaces:**
- Consumes: Task 1 `GroupApi`, `GroupSummary`, `MemberGroupDetailResponse`
- Produces:
  ```kotlin
  interface GroupReader {
      fun findSummaries(memberIds: Collection<String>): Map<String, MemberGroupSummary>   // key = memberId, 요청하지 않은 ID는 제외
      fun findDetail(memberId: String): MemberGroupDetailResponse?                       // 소속 없음 = null
  }
  data class MemberGroupSummary(val groupName: String, val permission: Permission)
  enum class GroupCallStage { CONCURRENCY_REJECTED, CIRCUIT_OPEN, CONNECT_TIMEOUT, READ_TIMEOUT, UPSTREAM_5XX, UPSTREAM_CONTRACT_ERROR, RETRY_BUDGET_EXCEEDED }
  class GroupServiceException(val stage: GroupCallStage, val attempts: Int, val elapsed: Duration, cause: Throwable?) : RuntimeException(cause)
  @ConfigurationProperties("app.group-client") data class GroupClientProperties(
      val concurrencyLimit: Int = 50,
      val retry: Retry = Retry(),           // maxRetries=1, delay=100ms, timeout=1500ms
      val circuitBreaker: Breaker = Breaker() // slidingWindowSize=20, minimumNumberOfCalls=10, failureRateThreshold=50f, waitDurationInOpenState=10s, permittedCallsInHalfOpenState=3
  )
  ```
- 설정(`application.yml`):
  - `spring.http.clients.imperative.factory: jdk`
  - `spring.http.serviceclient.group.base-url: http://127.0.0.1:8081`
  - `spring.http.serviceclient.group.connect-timeout: 500ms`
  - `spring.http.serviceclient.group.read-timeout: 1s`
  - `GroupClientConfig`에는 `@ImportHttpServices(group = "group", types = [GroupApi::class])`를 둔다.

- [ ] **Step 1: 실패하는 테스트 작성.** `GroupServiceGatewayTest`는 `@SpringBootTest(webEnvironment = NONE)` + Testcontainers + `MockWebServer`로 작성하고, `@DynamicPropertySource`로 base-url을 MockWebServer에 맞춘다. 각 테스트는 `assertTimeoutPreemptively(Duration.ofSeconds(4))`로 감싸고, 실제 경과 시간을 측정해 단언한다.
  - `slowHeadersFailWithinBudget()`: `headersDelay(5s)`. `READ_TIMEOUT` 또는 `RETRY_BUDGET_EXCEEDED`, 경과 < 3s, `server.requestCount <= 2`
  - `slowBodyFailsWithinBudget()`: 헤더는 즉시, `bodyDelay(5s)`. 위와 같은 단언이다. **이 테스트가 실패하면 Step 3의 hard deadline을 적용한다.**
  - `connectionRefusedIsConnectTimeoutStage()`: 닫힌 포트. `CONNECT_TIMEOUT`, 시도 2회
  - `fivexxIsRetriedOnceThenUpstream5xx()`: 503 두 번. `UPSTREAM_5XX`, `requestCount == 2`
  - `retrySucceedsAfterOne5xx()`: 503, 200 순서. 성공, `requestCount == 2`
  - `concurrencyLimitRejectsImmediately()`: `concurrencyLimit=1`로 오버라이드한다. 느린 응답 1건이 진행 중일 때 두 번째 호출은 즉시(<100ms) `CONCURRENCY_REJECTED`이고 `requestCount == 1`이다(시나리오 #4).
  - `openCircuitFailsFastWithoutRetry()`: 실패를 쌓아 OPEN으로 만든 뒤 호출하면 `CIRCUIT_OPEN`이고 추가 requestCount가 0이다(시나리오 #6).
  - `halfOpenRecoversOnNotFoundOnly()`: `waitDurationInOpenState=200ms`로 오버라이드한다. OPEN → 대기 → 404(`code=MEMBER_GROUP_NOT_FOUND`) 3회 → 상태가 CLOSED다(시나리오 #10).
  - `contractErrorsAreNotRetried()`: 파라미터화한다. code 없는 404, 400, 깨진 JSON, 알 수 없는 enum 값이 모두 `UPSTREAM_CONTRACT_ERROR`이고 `requestCount == 1`이다(시나리오 #11).
  - `unrequestedMembersInSummaryAreIgnored()`
  - `notFoundWithCodeReturnsNullDetail()`
- [ ] **Step 2:** 실행 `./gradlew :member-service:test --tests '*GroupServiceGatewayTest'`. Expected: FAIL.
- [ ] **Step 3:** `GroupServiceGateway : GroupReader`를 구현한다. 순서는 반드시 아래와 같다(spec §8.2).
  ```kotlin
  private fun <T> call(op: String, block: () -> T): T {
      val started = System.nanoTime()
      if (!permits.tryAcquire()) throw fail(CONCURRENCY_REJECTED, attempts = 0, started, null)
      inUse.incrementAndGet()
      val attempts = AtomicInteger()
      val timedOut = AtomicBoolean()          // RetryListener.onRetryPolicyTimeout 에서 true
      try {
          return retryTemplate(timedOut).execute {   // RetryPolicy: maxRetries, delay, timeout, predicate = ::isRetryable
              attempts.incrementAndGet()
              circuitBreaker.executeSupplier { block() }
          }
      } catch (e: RetryException) {
          throw fail(if (timedOut.get()) RETRY_BUDGET_EXCEEDED else classify(e.cause), attempts.get(), started, e.cause)
      } catch (e: Exception) {                 // 첫 시도 비재시도 예외 (4xx/계약/서킷 열림)
          throw fail(classify(e), attempts.get(), started, e)
      } finally { inUse.decrementAndGet(); permits.release() }
  }
  ```
  - **분류 규칙** `classify(e)`:
    - `CallNotPermittedException` → `CIRCUIT_OPEN`
    - cause 체인에 `HttpConnectTimeoutException` 또는 `ConnectException` → `CONNECT_TIMEOUT`
    - `HttpTimeoutException`/`SocketTimeoutException` → `READ_TIMEOUT`
    - `HttpServerErrorException` → `UPSTREAM_5XX`
    - 그 외 `HttpClientErrorException`, `RestClientException`(파싱 실패) → `UPSTREAM_CONTRACT_ERROR`
  - **재시도 대상** `isRetryable`: `CONNECT_TIMEOUT`, `READ_TIMEOUT`, 그리고 502/503/504인 `UPSTREAM_5XX`
  - **404 + `MEMBER_GROUP_NOT_FOUND` 처리**는 `block()` 안쪽, 즉 서킷 안쪽에서 한다. 본문을 `ProblemDetail`로 읽어 `code`를 확인하고 `null`을 반환하므로 서킷에는 성공으로 기록된다.
  - **CircuitBreakerConfig**: `COUNT_BASED`, `recordException { stage in (CONNECT_TIMEOUT, READ_TIMEOUT, UPSTREAM_5XX) }`. `CircuitBreakerRegistry`에 `TaggedCircuitBreakerMetrics`를 `MeterRegistry`로 바인딩한다.
  - **지표**: `group.call.permits.in_use` gauge(`inUse`), `group.call.failures{stage}` counter, `group.call.duration{outcome}` timer(percentile histogram).
  - **실패 로그**: `fail()`이 WARN 한 줄을 남긴다. 형식은 `group call failed op={} stage={} attempts={} elapsedMs={} cause={}`다.
  - **(조건부) hard deadline**: `slowBodyFailsWithinBudget`이 실패하면 `block()`을 가상 스레드 executor에 제출하고 `future.get(connectTimeout + readTimeout)`으로 기다린다. 시간을 넘기면 `cancel(true)` 후 `READ_TIMEOUT`으로 처리한다. 이 결정을 README에 남긴다.
- [ ] **Step 4:** Step 2를 다시 실행한다. Expected: PASS(전체 11개).
- [ ] **Step 5:** 커밋 `feat(member): resilient group service gateway`

---

### Task 6: member-service 조회 핸들러와 웹 계층

**Files:**
- Create: `query/GetMembers.kt`, `query/GetMemberDetail.kt`, `web/MemberController.kt`, `web/MemberExceptionHandler.kt`, `web/ProblemDetails.kt`
- Test: `member-service/src/test/kotlin/dev/task/member/web/MemberApiTest.kt`

**Interfaces:**
- Consumes: Task 1 `MemberApi`과 DTO, Task 4 `MemberReadRepository`/`Pagination`/`CursorCodec`, Task 5 `GroupReader`/`GroupServiceException`
- Produces:
  ```kotlin
  data class GetMembersQuery(val size: Int, val direction: SortDirection, val pagination: Pagination) {
      companion object { fun of(size: Int?, sort: String?, pagination: String?, page: Int?, cursor: String?): GetMembersQuery }  // 위반 시 InvalidRequestException
  }
  class GetMembersHandler { fun handle(query: GetMembersQuery): MemberListResponse }
  class GetMemberDetailHandler { fun handle(memberId: String): MemberDetailResponse }   // 없으면 MemberNotFoundException
  class MemberNotFoundException(val memberId: String) : RuntimeException()
  fun problem(status: HttpStatus, code: String, detail: String?, traceId: String?): ProblemDetail
  ```
- 검증 규칙(`GetMembersQuery.of`):
  - `size`는 1..100, 기본 20
  - `sort`는 `name,asc`/`name,desc`, 기본 asc
  - `pagination`은 `offset`/`cursor`, 기본 offset
  - `page`는 offset 모드에서만 허용하고 0 이상
  - `cursor`는 cursor 모드에서만 허용한다. decode 결과의 direction이 `sort`와 다르면 400이다.
  - path의 `memberId`는 정규식 `^[a-z0-9]{10}$`

- [ ] **Step 1: 실패하는 테스트 작성** (`@SpringBootTest(RANDOM_PORT)` + Testcontainers + MockWebServer, `RestTestClient` 또는 `MemberApi` 프록시 사용):
  - `listJoinsGroupInfoAndCallsGroupOncePerPage()`: 응답 `members[*].group/permission`이 MockWebServer 응답과 일치하고, `requestCount == 1`이며, 그 요청의 `memberIds` 집합이 페이지의 ID 집합과 같다.
  - `ungroupedMemberHasNullGroup()`
  - `detailCombinesGroupDetail()`, `detailWithMemberGroupNotFoundHasNullGroup()`
  - `unknownMemberIs404WithoutCallingGroup()`: `code == "MEMBER_NOT_FOUND"`, `requestCount == 0`
  - `invalidRequestsAre400WithoutAnyGroupCall()`: 파라미터화한다(시나리오 #3).
    - `page`+`cursor` 동시 지정
    - `pagination=cursor&page=1`
    - `size=0`, `size=101`
    - `sort=email,asc`
    - 깨진 cursor
    - 방향이 다른 cursor
    - `page=-1`, `size=abc`
    - 모두 `code == "INVALID_REQUEST"`, `requestCount == 0`
  - `invalidMemberIdIs400WithoutAnyCall()`
  - `emptyPageReturns200WithoutCallingGroupService()`: `page=999`면 200, `members == []`, `requestCount == 0`
  - `groupFailureIs503WithCodeAndTraceIdKey()`: MockWebServer가 503을 두 번 주면 응답은 503, `code == "UPSTREAM_5XX"`, `traceId` 키가 있다.
  - `circuitOpenAddsRetryAfter()`: `Retry-After == "10"`
  - `dbConnectionIsReleasedDuringGroupCall()`: MockWebServer `headersDelay(800ms)`로 요청을 백그라운드에 보내고, 400ms 시점에 `HikariDataSource.hikariPoolMXBean.activeConnections == 0`인지 확인한다(시나리오 #12).
- [ ] **Step 2:** 실행 `./gradlew :member-service:test --tests '*MemberApiTest'`. Expected: FAIL.
- [ ] **Step 3:** Handler를 구현한다. Handler에는 트랜잭션을 걸지 않는다. 순서는 다음과 같다.
  1. `MemberReadRepository` 호출. 트랜잭션이 끝난다.
  2. rows가 비어 있으면 그룹 호출을 생략한다.
  3. `GroupReader.findSummaries(ids)`
  4. DTO를 조립한다. 시각은 `app.time-zone`의 `LocalDateTime`으로 바꾸고, cursor는 `CursorCodec.encode`로 만든다.
- [ ] **Step 4:** `MemberController : MemberApi`, `MemberExceptionHandler`를 구현한다.
  - `InvalidRequestException`과 타입 변환 실패(`MethodArgumentTypeMismatchException`) → 400 `INVALID_REQUEST`
  - `MemberNotFoundException` → 404 `MEMBER_NOT_FOUND`
  - `GroupServiceException` → 503, `code = stage.name`. `CIRCUIT_OPEN`이면 `Retry-After = waitDurationInOpenState.seconds`를 붙인다.
- [ ] **Step 5:** Step 2를 다시 실행한다. Expected: PASS.
- [ ] **Step 6:** 커밋 `feat(member): member list/detail queries and contract controller`

---

### Task 7: 관측성 (추적, 로그, 지표, 관리 포트)

**Files:**
- Modify: 두 서비스의 `build.gradle.kts`, `application.yml`, `MemberExceptionHandler`/`GroupExceptionHandler`(traceId 주입). Create: `member-service/src/main/resources/application-prod.yml`, `group-service/src/main/resources/application-prod.yml`
- Test: `member-service/src/test/kotlin/dev/task/member/ObservabilityTest.kt`

**Interfaces:**
- Consumes: Task 5 실패 로그 형식, Task 6 `problem(...)`
- Produces: 응답 `traceId`, `traceparent` 전파, `/actuator/prometheus`(관리 포트)

- [ ] **Step 1:** 두 서비스에 의존성을 추가한다: `spring-boot-starter-actuator`, `org.springframework.boot:spring-boot-micrometer-tracing-brave`, `io.micrometer:micrometer-tracing-bridge-brave`, `io.micrometer:micrometer-registry-prometheus`. 버전은 모두 Boot가 관리한다.
- [ ] **Step 2:** 설정을 추가한다.
  - `management.server.port` 9080/9081, `management.server.address: 127.0.0.1`
  - `management.endpoints.web.exposure.include: health,info,prometheus`
  - `management.endpoint.health.probes.enabled: true`. readiness 그룹은 `readinessState,db`로, member에 그룹 서비스를 넣지 않는다.
  - `management.tracing.sampling.probability: 1.0`, `management.tracing.export.enabled: false`
  - `management.metrics.distribution.percentiles-histogram.http.client.requests: true`
  - `application-prod.yml`: `logging.structured.format.console: ecs`, `spring.flyway.locations: classpath:db/migration`
- [ ] **Step 3: 실패하는 테스트 작성** (`@SpringBootTest(RANDOM_PORT)` + `@ExtendWith(OutputCaptureExtension::class)` + MockWebServer, `@AutoConfigureObservability`가 필요하면 적용):
  - `traceIdPropagatesToGroupServiceAndIntoProblem()`: MockWebServer가 503을 두 번 준다. 응답 `traceId`가 비어 있지 않고, 기록된 **두 요청 모두** `traceparent` 헤더의 trace-id 부분(`00-<traceId>-...`)이 응답 `traceId`와 같다.
  - `failureLogCarriesTraceIdAndStage()`: 캡처한 출력에 `stage=UPSTREAM_5XX`가 포함되고, 같은 줄에 응답 `traceId`가 있다.
  - `successPathAlsoPropagates()`
  - `prometheusExposesGatewayMetrics()`: 관리 포트 `/actuator/prometheus` 응답에 `group_call_failures_total`, `group_call_duration_seconds_bucket`, `resilience4j_circuitbreaker_state`가 있다.
  - `readinessIgnoresGroupService()`: MockWebServer를 shutdown한 상태에서도 `/actuator/health/readiness`가 `UP`이다.
- [ ] **Step 4:** 실행 `./gradlew :member-service:test --tests '*ObservabilityTest'`. Expected: FAIL.
- [ ] **Step 5:** 두 ExceptionHandler에서 `Tracer.currentSpan()?.context()?.traceId()`를 `traceId`로 넣는다. 로그 패턴은 Boot 기본 correlation을 쓴다. 출력에 traceId가 없으면 `logging.pattern.correlation`을 명시한다.
- [ ] **Step 6:** Step 4를 다시 실행하고, `./gradlew test`도 실행한다. Expected: 전체 PASS.
- [ ] **Step 7:** 커밋 `feat: tracing, structured logs, prometheus metrics on loopback management port`

---

### Task 8: OpenAPI, README, smoke 스크립트, 제출 검증

**Files:**
- Modify: 두 서비스의 `build.gradle.kts`(`org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0`), `README.md`
- Create: `scripts/smoke.sh`

- [ ] **Step 1:** springdoc을 추가한다. OpenAPI 어노테이션은 **컨트롤러에** 둬서 계약 모듈의 Boot 비의존을 유지한다(spec §10 builder 판단). 각 서비스에서 `/v3/api-docs`가 200을 주는지 테스트 `openApiDocIsServed()`로 확인한다. member 쪽은 Task 6 테스트 클래스에, group 쪽은 Task 3 테스트 클래스에 추가한다.
- [ ] **Step 2:** `scripts/smoke.sh`를 만든다(`set -euo pipefail`). 순서는 다음과 같다.
  1. `docker compose up -d --wait`
  2. 두 서비스 `bootRun`을 백그라운드로 실행하고 `/actuator/health/readiness`를 최대 90초 polling한다.
  3. curl로 검사한다. `/api/members?size=5` 200 + `.members | length == 5`, `pagination=cursor` 200 + `.cursor.hasNext`, 그룹 없는 멤버 상세 200 + `.group == null`, `/api/member/ABC` 400
  4. trap으로 프로세스와 compose를 정리한다.
  - jq를 필수로 쓰고, 없으면 안내 메시지를 낸다.
- [ ] **Step 3:** README를 작성한다. spec §13의 필수 섹션을 모두 담는다.
  - 사전 요구사항: JDK 25, Docker + Compose v2
  - 실행 절차, curl 예시, 모듈 구조와 "직접 참조 금지"의 근거
  - CQRS 의도: 왜 command가 없는지, 생기면 어디에 붙는지
  - 장애 처리 표와 시간 예산 계산, 모니터링 지표와 PromQL 알림 3개
  - 해석/판단 목록: spec의 [판단] 항목과 Task 4/6의 fallback 적용 여부
  - 기존 AI 활용/로깅 섹션
- [ ] **Step 4: 제출 검증.** `rm -rf /tmp/task-verify && git clone . /tmp/task-verify && cd /tmp/task-verify && ./gradlew build && scripts/smoke.sh && scripts/check-forbidden.sh`. Expected: 모두 exit 0. 실패하면 원인을 고친 뒤 다시 실행한다.
- [ ] **Step 5:** 커밋 `docs: README, OpenAPI and smoke script`
