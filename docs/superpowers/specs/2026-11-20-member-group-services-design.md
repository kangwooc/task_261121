# 멤버 조회 / 그룹 조회 서비스 설계

- 작성일: 2026-11-20
- 상태: 검토 반영본 (사용자 승인 대기). 검토 에이전트의 builder check와 Codex CLI 교차 리뷰를 반영함
- 표기: **[결정]** 사용자와 합의한 사항 · **[판단]** 설계자가 기본값으로 정한 사항(변경 가능) · **[미검증]** 구현 계획 단계에서 확인 필요

---

## 1. 의도

채용 과제다. 메시지 브로커 없이 물리적으로 분리된 두 마이크로서비스(외부 공개 `member-service`, 내부 전용 `group-service`)를 구현한다. 면접에서 이 코드를 기반으로 코드 리뷰가 진행되므로, **결과물보다 "왜 이렇게 만들었는가"가 코드와 README에서 드러나는 것**이 핵심 목적이다.

보여주려는 것:
- 서비스 경계와 계약(contract) 소유권을 명확히 한 멀티모듈 구조
- 조회만 존재하는 상황에서의 정직한 CQRS (구조로 분리를 보여줌, 빈 껍데기 없음)
- 내부 서비스 장애 시 "실패를 숨기지 않고, 정해진 시간 안에, 원인이 드러나게" 실패하는 설계
- 그 설계가 실제로 동작함을 증명하는 테스트

## 2. 목표 / 비목표 / 안티 목표

**목표**
- 과제의 필수 API 4개를 요구 시그니처대로 구현
- `git clone` → 빌드 → 실행 → 결과 확인이 README 절차대로 반드시 성공
- CQRS 구조, 성공/실패 테스트 (필수)
- 로그, OpenAPI, 변경에 쉬운 구조 (선택 항목 전부)

**비목표**
- 쓰기(Command) API. 과제 API는 전부 조회다. [결정]
- 인증/인가, API Gateway, 서비스 디스커버리, 메시지 브로커
- Prometheus/Grafana 컨테이너, 대시보드 JSON. 지표 노출 + README 알림 기준까지만. [결정]
- 트레이스 수집 백엔드(Zipkin 등)로의 export

**안티 목표 (기술적으로 동작해도 실패로 간주)**
- 그룹 서비스 장애를 빈 배열이나 빈 그룹으로 숨기는 것 [결정]
- clone 후 빌드나 실행이 실패하는 것 (과제 실격)
- 저장소 어디든(코드, 패키지명, 문서, 로그) 회사명이 등장하는 것 (과제 실격)
- `*-service` 모듈끼리 직접 의존하는 것
- 쓰지 않는 계층, 인터페이스, 패키지를 "나중을 위해" 만드는 것

## 3. 제약

| 항목 | 값 |
|---|---|
| JDK | 25 |
| Kotlin | 2.3.21 (Boot 관리 버전) |
| Spring Boot | **4.1.3** [결정] (Spring Framework 7.0.11 포함). 4.2.0은 2026-11-19 출시로 생태계 미성숙 |
| Gradle | 9.3.1 (Kotlin Gradle plugin 2.3.x 공식 지원 범위 상한) |
| DB | PostgreSQL [결정] |
| Testcontainers | 2.0.5 (Boot 관리). 아티팩트 `org.testcontainers:testcontainers-postgresql` |
| Flyway | Boot 관리 버전(12.4.0). `spring-boot-starter-flyway` + `flyway-database-postgresql` |
| JPA | `spring-boot-starter-data-jpa` (Hibernate는 Boot 관리 버전), Kotlin `plugin.jpa` |
| springdoc-openapi | 3.2.0 |
| Resilience4j | 2.4.0. **starter 없이** `resilience4j-circuitbreaker`, `resilience4j-micrometer`만 사용 |
| Jackson | 3.x (`tools.jackson.*`, 어노테이션은 `com.fasterxml.jackson.annotation`) |

- Boot가 관리하는 의존성 버전은 덮어쓰지 않는다.
- Kotlin 컴파일러 옵션 `-Xannotation-default-target=param-property`, `kotlin("plugin.spring")` 적용 (Boot 4.1 Kotlin 가이드).
- Boot 4 starter 이름 변경을 반영한다: `spring-boot-starter-webmvc`(구 web), `spring-boot-starter-restclient`, `spring-boot-starter-webmvc-test`, `spring-boot-testcontainers`. `spring-boot-docker-compose`는 쓰지 않는다(§11).
- **Docker는 필수 실행 조건이다.** [결정] 실행(`docker compose`)과 테스트(Testcontainers)가 모두 Docker를 사용하며, README의 "사전 요구사항"에 명시한다(Docker Engine/Desktop, Compose v2). Docker 없이 도는 대체 경로(H2, embedded Postgres)는 만들지 않는다.
- **저장소는 비공개로 만들고 채점자를 초대한다.** [결정] AI 원본 로그에 과제 원문이 들어 있어서, 과제 내용 외부 공유 금지 조항과 충돌하지 않게 하기 위해서다.
- 저장소명은 `task_{과제시작일 yyyyMMdd}` 형식이다. 패키지명, 모듈명, 문서, 로그에 회사명을 쓰지 않는다.
- AI 세션 로그: `.claude/hooks/save-transcript.sh`가 `ai-logs/claude-code/`에 원본을 마스킹해 저장한다(이미 구성됨). README에 해당 섹션이 있다.

## 4. 모듈 구조 [결정]

```
root
├── group-api        계약: DTO + GroupApi(@HttpExchange). 그룹 서비스가 소유/배포하는 모듈
├── group-service    GroupApi를 @RestController로 구현. group DB 사용. 내부 전용
├── member-api       계약: DTO + MemberApi(@HttpExchange). 프런트/타 서비스 공유용
└── member-service   MemberApi 구현. member DB 사용. group-api 인터페이스로 HTTP 클라이언트 생성
```

| 모듈 | 의존 |
|---|---|
| `group-api`, `member-api` | `spring-web`(어노테이션), Jackson 어노테이션만. Spring Boot 의존 없음 |
| `group-service` | `group-api` |
| `member-service` | `member-api`, `group-api` |

- `*-service` 끼리는 main/test 어느 scope로도 의존하지 않는다. 공유는 계약 모듈뿐이고 호출은 네트워크로만 한다. 이것이 "직접 참조 금지"를 지키는 근거이며 README에 명시한다.
- 실무라면 `*-api`는 사내 저장소에 배포되는 라이브러리다. 과제에서는 Gradle project 의존으로 대신하고 README에 기술한다.
- 서버와 클라이언트가 **동일한 `@HttpExchange` 인터페이스**를 공유한다. 서버는 컨트롤러가 인터페이스를 구현하고(Spring 6.1+), 클라이언트는 Boot 4 HTTP Service Client(`@ImportHttpServices(group = "group", types = [GroupApi::class])`)로 프록시를 생성한다.
- `common` 모듈, `build-logic`(convention plugin)은 두지 않는다. 루트 `build.gradle.kts` + `gradle/libs.versions.toml`로 관리한다. [판단]
- 기본 패키지: `<base>.member`, `<base>.group`. `<base>`는 구현 시 지원자 GitHub id 기반(`io.github.<id>`)으로 정한다. [판단]

## 5. API 명세

공통:
- 모든 오류 응답은 `application/problem+json`(RFC 9457 `ProblemDetail`)이고 확장 필드 `code`, `traceId`를 포함한다.
- 날짜 표기는 `"yyyy-MM-dd HH:mm"`이다. DB에는 `timestamptz`로 저장하고 `app.time-zone`(기본 `Asia/Seoul`)으로 렌더링한다. [판단]

### 5.1 member-service (외부 공개, 포트 8080)

#### `GET /api/members` — 멤버 목록

| 파라미터 | 규칙 |
|---|---|
| `size` | 1..100, 기본 20 |
| `sort` | `name,asc` \| `name,desc`, 기본 `name,asc`. 그 외 정렬 기준은 400 |
| `pagination` | `offset` \| `cursor`, 기본 `offset` [판단] |
| `page` | 0 이상. `pagination=offset`에서만 허용 |
| `cursor` | 불투명 문자열. `pagination=cursor`에서만 허용. 없으면 cursor 방식의 첫 페이지 |

- 두 방식을 모두 지원한다. [결정] 방식과 맞지 않는 파라미터(예: `pagination=cursor&page=2`, `page`와 `cursor` 동시 지정)는 400이다. `pagination`을 명시하는 이유는 cursor 방식의 **첫 페이지 진입 경로**가 필요하기 때문이다(Codex 리뷰 반영).
- `hasNext`는 `size + 1`건을 조회해서 판정한다. offset 방식은 count 쿼리를 1회 추가로 실행한다.
- **정렬 키는 `name → registered_at → id`이고, 세 컬럼 모두 요청 방향을 따른다.** [결정] 동명이인은 가입일 순이고, 같은 시각이면 id로 결정한다. 방향을 통일한 이유는 PostgreSQL row 비교 `(name, registered_at, id) > (:n, :r, :i)` 한 줄로 keyset 조건을 쓰고 인덱스를 역방향 스캔으로도 쓰기 위해서다.
- cursor는 마지막 행의 `(name, registeredAt, id)`와 정렬 방향을 base64url(JSON)로 인코딩한다. `registeredAt`은 화면 표시값(분 단위)이 아니라 **DB 원본 정밀도(µs, epoch micros)**로 담는다. 디코딩에 실패하거나 cursor의 방향이 `sort`와 다르면 400이다. [판단]
- 한 페이지의 멤버 ID를 모아 그룹 서비스 `GET /api/groups/summary`를 **페이지당 논리적으로 1회(배치 1건)** 호출한다. 실제 HTTP 시도는 재시도를 포함해 최대 2회다. 결과가 0건인 페이지는 호출하지 않는다.

응답 200 (과제 시그니처의 `members` 유지 + 페이지 메타 추가):
```json
{
  "members": [
    { "id": "tu3fgxd45k", "name": "user1", "group": "Test1", "permission": "Editor" },
    { "id": "q8zk2m1x0a", "name": "user3", "group": null, "permission": null }
  ],
  "page": { "number": 0, "size": 20, "totalElements": 42, "totalPages": 3 },   // offset 방식일 때
  "cursor": { "next": "eyJu...", "hasNext": true }                             // cursor 방식일 때
}
```
`page`와 `cursor`는 사용한 방식에 해당하는 것 하나만 포함한다. 그룹이 없는 멤버는 `group`, `permission`이 `null`이다.

#### `GET /api/member/{memberId}` — 멤버 상세 [판단: path variable]

응답 200:
```json
{
  "id": "tu3fgxd45k", "name": "user1", "email": "user1@company.com",
  "address": "seoul, gangnamgu", "registeredDate": "2026-08-15 13:00",
  "group": { "id": "arutjf9348", "name": "Test1", "registeredDate": "2026-08-15 13:00",
             "pricing": "Premium", "permission": "Editor" }
}
```
- `registeredDate`는 멤버 가입 시각이다.
- `group.registeredDate`는 그룹 상세 API가 주는 "멤버가 그룹에 추가된 시각"을 그대로 쓴다. 그룹 생성일은 계약에 없으므로 이렇게 해석한다. [판단]
- 그룹 서비스가 404 + `code=MEMBER_GROUP_NOT_FOUND`를 주면 `"group": null`로 200 응답한다. [결정] 이 code가 없는 404(예: base-url 오설정)는 장애(`UPSTREAM_CONTRACT_ERROR`)로 처리한다.
- 멤버가 없으면 404이고 `code=MEMBER_NOT_FOUND`이다. 이 경우 그룹 서비스는 호출하지 않는다.

### 5.2 group-service (내부 전용, 포트 8081)

#### `GET /api/groups/summary?memberIds=a,b,c`
- `memberIds`는 1..100개이고(member 페이지 크기 상한과 동일), 중복은 제거한다. 비었거나 개수가 초과되거나 형식이 잘못되면 400이다.
- 응답은 과제 시그니처 그대로다. `totalMemberCount`는 **그룹 전체 멤버 수**이고, `members`는 요청된 ID 중 해당 그룹 소속인 멤버만 담는다. [판단]
- 어떤 그룹에도 속하지 않은 ID는 응답에 나오지 않는다. 오류가 아니다.

#### `GET /api/group?memberId=x` [판단: query string]
- 응답은 과제 시그니처 그대로다(`registeredDate` = 멤버가 그룹에 추가된 시각).
- 소속이 없으면 404이고 `code=MEMBER_GROUP_NOT_FOUND`이다.

### 5.3 열거형
- `Pricing`: `Free`, `Advanced`, `Premium`
- `Permission`: `Viewer`, `Editor`, `Admin`

JSON 값은 위 표기 그대로다. DB에는 CHECK 제약이 있는 `varchar`로 저장한다.

## 6. 데이터 [결정: 서비스별 DB 분리]

**member DB** (`member-db`, 호스트 포트 15432. 로컬에 설치된 Postgres의 5432와 충돌하지 않게 기본 포트를 피함)
```sql
members(id varchar(10) pk, name varchar not null, email varchar not null unique,
        address varchar not null, registered_at timestamptz not null)
index members_name_sort_idx on members(name, registered_at, id)
```

**group DB** (`group-db`, 호스트 포트 15433)
```sql
groups(id varchar(10) pk, name varchar not null, pricing varchar not null check(...),
       registered_at timestamptz not null)
group_members(member_id varchar(10) pk,            -- 멤버 ID 논리 키. pk → 멤버는 1개 그룹에만 소속
              group_id varchar(10) not null references groups(id),
              permission varchar not null check(...), joined_at timestamptz not null)
index group_members_group_idx on group_members(group_id)
```

- ID는 과제 예시처럼 소문자와 숫자로 된 10자 무작위 문자열이다. 시간 순서 의미가 없다.
- 스키마와 시드는 Flyway로 관리한다. 위치는 `db/migration`(스키마)와 `db/seed`(샘플 데이터)이고, 기본 프로필은 두 위치를 모두 로드하며 `prod` 프로필은 `db/migration`만 로드한다. [판단]
- 시드 데이터는 두 DB 간 멤버 ID를 일치시키고 다음을 반드시 포함한다. [판단]
  - **동명이인 + 동일 가입시각** 그룹
  - 그룹이 없는 멤버
  - 3가지 pricing, 3가지 permission
  - 3페이지 이상이 나오는 규모(약 50명)

## 7. CQRS 구조 [결정: 조회만, 구조로 분리를 보여줌]

각 서비스의 패키지 구성은 다음과 같다(예: member-service).
```
member/
├── web/              MemberController (MemberApi 구현), 요청 검증, ProblemDetail 매핑
├── query/            GetMembersQuery, GetMemberDetailQuery
│                     GetMembersQueryHandler, GetMemberDetailQueryHandler
│                     MemberSummaryView, MemberDetailView (조회 모델)
├── query/port/       GroupReader (외부 서비스 포트. 구현이 바뀔 가능성이 실제로 있는 경계만 인터페이스로 둔다)
└── infrastructure/
    ├── persistence/  MemberEntity, MemberJpaRepository (Spring Data JPA, DTO projection 쿼리)
    │                 MemberReadRepository (Repository를 감싸 조회 모델을 반환하는 읽기 전용 트랜잭션 경계)
    └── group/        GroupServiceGateway (GroupReader 구현, 탄력성 조합)
```
- 컨트롤러는 Query 객체를 만들어 Handler에 위임한다. Handler는 그룹 조회를 `GroupReader` 포트로만 한다. 그래서 그룹 조회 방식(HTTP → 캐시/이벤트 프로젝션 등)이 바뀌어도 Handler는 바뀌지 않는다. 이것이 "설계 변경에 쉬운 구조"의 근거다.
- 구현이 하나뿐이고 바뀔 이유가 없는 저장소에는 인터페이스를 두지 않는다. Query 객체는 검증된 조회 조건(페이징, 정렬)을 담는 책임이 있을 때만 만든다. 위임만 하는 타입은 만들지 않는다(Codex 리뷰 반영). [판단]
- **조회는 Spring Data JPA(Hibernate, Boot 관리 버전)로 한다.** [결정]
  - 엔티티(`MemberEntity`, `GroupEntity`, `GroupMemberEntity`)는 테이블 매핑만 담당한다.
  - 조회 모델은 엔티티를 그대로 내보내지 않고 **DTO projection**(JPQL constructor expression 또는 Spring Data class-based projection)으로 바로 만든다. 엔티티를 로딩하지 않으므로 dirty checking이나 지연 로딩이 끼어들 여지가 없다.
  - `spring.jpa.open-in-view=false`가 **필수**다. 기본값(true)이면 요청이 끝날 때까지 커넥션이 잡혀 있어서, 아래 "트랜잭션 범위" 설계가 무력화된다.
  - `spring.jpa.hibernate.ddl-auto=validate`로 둔다. 스키마는 Flyway가 소유한다.
  - `@Transactional(readOnly = true)`를 쓰면 Hibernate가 flush를 생략하고 세션을 읽기 전용으로 둔다.
  - Kotlin 설정: `kotlin("plugin.jpa")`(no-arg)를 쓰고, `@Entity`/`@MappedSuperclass`/`@Embeddable`에 all-open을 적용한다.
  - **keyset 조건**: HQL 튜플 비교 `(m.name, m.registeredAt, m.id) > (:name, :registeredAt, :id)`를 쓰는 `@Query`로 구현한다. 방향에 따라 `>`/`<`와 `ORDER BY`를 바꾼다. [미검증: Hibernate 7 + PostgreSQL에서 row value 비교로 번역되는지. 안 되면 Spring Data `Window`/`ScrollPosition.keyset()`으로 대체한다. 이 경우 OR로 풀린 조건이 생성된다]
  - offset 방식은 Spring Data `Pageable`/`Page`를 쓴다. 정렬은 `Sort.by(dir, "name", "registeredAt", "id")`이다.
- **트랜잭션 범위**: Handler에는 트랜잭션을 걸지 않는다. DB 조회(목록 + count)만 `MemberReadRepository`의 `@Transactional(readOnly = true)` 메서드 안에서 끝내고 커넥션을 반환한 다음, 그룹 서비스를 호출한다. 그룹 호출이 재시도되는 동안 DB 커넥션을 붙잡지 않기 위해서다(Codex 리뷰 반영).
- `command/` 패키지는 만들지 않는다. README의 "CQRS" 절에서 쓰기가 생기면 어디에 무엇이 붙는지(`command/`, 쓰기 모델, 동기화 방식)를 설명한다.
- 페이징 방식(offset/cursor)은 `GetMembersQuery` 안의 sealed 타입(`Pagination.Offset` / `Pagination.Cursor`)으로 표현한다. [판단]
- 동시성 모델은 가상 스레드다(`spring.threads.virtual.enabled=true`). 블로킹 MVC + JDBC + RestClient를 쓰고, 코루틴은 쓰지 않는다. [결정] Spring 재시도는 `suspend`를 지원하지 않는다.

## 8. 서비스 간 호출과 장애 처리 [결정]

### 8.1 원칙
- "그룹 없음"(그룹 서비스가 정상 응답했으나 소속 없음)은 장애가 아니다. 200에 `group: null`로 응답한다.
- 그룹 서비스 장애는 **503 + ProblemDetail**이다. 빈 배열이나 null로 숨기지 않는다.
- 응답 `code`, 로그 `stage`, 지표 태그 `stage`는 **같은 값**을 쓴다.

| code / stage | 원인 | 비고 |
|---|---|---|
| `CONCURRENCY_REJECTED` | 동시 호출 상한 초과 | 그룹 서버 호출 없음 |
| `CIRCUIT_OPEN` | 서킷 열림 | 그룹 서버 호출 없음, `Retry-After` 헤더 = 서킷 open 대기 시간 |
| `CONNECT_TIMEOUT` | 연결 타임아웃 / 연결 실패 | |
| `READ_TIMEOUT` | 응답 타임아웃 | |
| `UPSTREAM_5XX` | 그룹 서비스 5xx | |
| `UPSTREAM_CONTRACT_ERROR` | 예상하지 못한 4xx(code 없는 404 포함), JSON 파싱 실패, 알 수 없는 enum, 빈 본문 | 재시도하지 않음. 사용자 입력 오류(400)와 구분 |
| `RETRY_BUDGET_EXCEEDED` | 재시도 예산 초과로 재시도 생략 후 실패 | 마지막 원인도 로그에 남김 |

모든 code는 member-service 응답에서 **503**으로 매핑한다. [결정]

### 8.2 조합 순서: 코드로 명시 (어노테이션 미사용)

Spring Framework 7.0.11 소스를 확인한 결과는 다음과 같다(scratch 확인, 근거는 아래).
- `@ConcurrencyLimit`과 `@Retryable`은 같은 order(`LOWEST_PRECEDENCE - 1`)를 가지며 `beforeExistingAdvisors=true`다. 결과적으로 동시 호출 제한이 바깥에 오지만, 이는 **bean 정의 순서에 의존하는 문서화되지 않은 동작**이다.
- 재시도 `timeout`은 **다음 시도 직전에만** 검사되며(`elapsed + sleep >= timeout`), 진행 중인 호출을 끊지 않는다(`RetryTemplate` 147–224행).
- 어노테이션 방식은 재시도 소진 시 마지막 원인 예외를 던진다(`AbstractRetryInterceptor` 150행). `RetryTemplate`을 직접 쓰면 `execute()`는 `RetryException`을, `invoke()`는 마지막 원인 예외를 던진다(231행).

따라서 `GroupServiceGateway`에서 Spring 7 core 재시도 API(`RetryTemplate`/`RetryPolicy`)와 Resilience4j를 **명시적 순서로 조합**한다.

```
① Semaphore.tryAcquire()  실패 → CONCURRENCY_REJECTED  (바깥: 재시도 내내 슬롯 1개 점유)
② RetryTemplate           maxRetries=1, delay=100ms, timeout=1.5s
③ CircuitBreaker          시도마다 성공/실패 기록
④ GroupApi(HTTP)          connect 0.5s / read 1s
finally: release()
```

- **`RetryTemplate.execute()`를 사용한다.** 종료 이유는 `RetryListener`로 기록한다: `onRetryPolicyTimeout` → `RETRY_BUDGET_EXCEEDED`, `onRetryPolicyExhaustion` → 마지막 원인에 따른 code. 예외의 `cause`만으로는 "예산 초과"와 "횟수 소진"을 구분할 수 없기 때문이다(Codex 리뷰 반영).
- **재시도 대상**: 연결 실패, 연결/응답 타임아웃, 502/503/504만이다. 4xx, `UPSTREAM_CONTRACT_ERROR`, `CallNotPermittedException`(서킷 열림), 동시 호출 거절은 재시도하지 않는다.
- **HTTP 구현을 고정한다**: JDK `HttpClient`를 쓴다(`spring.http.clients.imperative.factory=jdk`) [미검증: 속성값]. Boot는 클래스패스에 따라 구현을 고르므로, 고정하지 않으면 타임아웃 의미가 바뀔 수 있다. 응답 본문은 버퍼링해서 읽는다(`String`/DTO 역직렬화).
- **시간 예산 계산**: 호출 1회 최대 1.5초(connect 0.5 + read 1.0), 재시도 `timeout`은 1.5초다. 그래서 재시도는 "경과시간 + 100ms < 1.5초"일 때만 시작되고, **최악의 총 소요 시간은 약 3초 미만**이다.
  - 이 계산은 "connect + read = 호출 1회 상한"이라는 전제에 기대고 있다. 이 전제가 고정한 HTTP 구현에서 **느린 헤더**와 **느린 본문(헤더 후 본문이 늦게 옴)** 모두에 대해 성립하는지 테스트로 검증한다. 성립하지 않으면 Gateway에 호출 단위 hard deadline(가상 스레드 + `Future.get(timeout)` 후 cancel)을 추가한다. (Codex 리뷰 반영)
- **서킷 브레이커 기본값** [판단]: count 기반 슬라이딩 윈도우 20, 최소 호출 10, 실패율 50%, open 대기 10초, half-open 허용 3회. 느린 호출은 타임아웃으로 실패 처리되므로 slow-call 규칙은 쓰지 않는다.
- **동시 호출 상한** 기본 50 [판단].
- 모든 수치는 `app.group-client.*` 프로퍼티로 외부화한다. 타임아웃은 `spring.http.serviceclient.group.{base-url, connect-timeout, read-timeout}`으로 설정한다.
- 그룹 서비스의 404 + `MEMBER_GROUP_NOT_FOUND`는 **서킷 내부(③ 안쪽)에서** `null`로 변환해 **성공으로 기록**한다. `ignoreExceptions`를 쓰면 안 된다. 무시된 호출은 성공 표본도 쌓지 않아서, half-open 상태에서 이 404만 계속 오면 CLOSED로 돌아가지 못한다(Codex 리뷰 반영, Resilience4j 2.4.0 `CircuitBreakerStateMachine`).
- 503 변환은 member-service의 `@RestControllerAdvice` 한 곳에서 한다.

## 9. 관측성

### 9.1 추적과 로그 [결정]
- Micrometer Tracing을 쓰고 W3C `traceparent`를 전파한다. Boot가 구성한 RestClient(HTTP Service Client 포함)는 자동으로 계측된다고 본다. [미검증: Boot 4.1의 정확한 tracing 의존성 이름, HTTP Service Client 자동 계측 여부]
- 로그 패턴에 traceId/spanId를 포함한다(Boot correlation 패턴). trace export는 하지 않는다.
- 그룹 호출이 실패하면 Gateway가 한 줄로 로그를 남긴다: `stage`, 시도 횟수, 경과 ms, 원인 예외 클래스.
- `ProblemDetail`에 `traceId`를 포함한다.
- 기본은 텍스트 로그다. `prod` 프로필은 Boot 구조화 로그(`logging.structured.format.console=ecs`)를 쓴다. [판단]

### 9.2 지표 / 상태 [결정]
- Actuator는 **별도 관리 포트**를 쓴다(member 9080, group 9081). 노출은 `health`, `info`, `prometheus`만 한다.
- readiness에는 **자기 DB만** 포함한다. member readiness에 그룹 서비스 상태를 넣지 않는다(연쇄 장애 방지).
- 의존성: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`. endpoint 노출만으로는 Prometheus registry가 생기지 않는다.
- 지표:
  - `http.server.requests`, `http.client.requests` (자동). `http.client.requests`는 **HTTP 시도 1회** 단위다.
  - `group.call.duration` (timer, 직접 생성): 재시도를 포함한 **Gateway 호출 전체** 시간. `outcome` 태그(success/code)를 단다.
  - `resilience4j.circuitbreaker.*` (`TaggedCircuitBreakerMetrics`로 바인딩)
  - `group.call.failures{stage}` (counter)
  - `group.call.permits.in_use` (gauge)
- p99 알림을 위해 `http.client.requests`와 `group.call.duration`에 percentile histogram을 켠다(`management.metrics.distribution.percentiles-histogram.*`). (Codex 리뷰 반영)
- README에 알림 기준 예시를 PromQL로 적는다:
  - member 503 비율 > 1% (5분)
  - 서킷 open 1분 이상 지속
  - 그룹 호출 p99가 read timeout(1초)에 근접

## 10. OpenAPI [판단]
- 두 서비스 모두 springdoc 3.2.0으로 `/v3/api-docs`, `/swagger-ui.html`을 노출한다.
- 계약 인터페이스(`*Api`)에 OpenAPI 어노테이션을 둘지, 컨트롤러에 둘지는 builder가 정한다. 계약 모듈을 Spring Boot 비의존으로 유지하는 쪽을 우선한다.

## 11. 실행 환경
- 포트: member 8080 / group 8081 / 관리 9080, 9081 / DB 15432, 15433
- "서로 다른 host" 요구는 서로 다른 포트(별도 프로세스)로 충족한다. member는 그룹 서비스를 `http://127.0.0.1:8081`로 호출하고(외부 공개는 `localhost:8080`), README에 이 해석을 명시한다. [판단]
- **내부 전용 경계**: group-service는 `server.address=127.0.0.1`, 두 서비스의 관리 포트는 `management.server.address=127.0.0.1`로 loopback에만 바인딩한다. member-service만 외부 인터페이스에 바인딩한다. (Codex 리뷰 반영) [판단]
- **Docker Compose: 루트 `compose.yaml` 하나**에 `member-db`, `group-db`를 둔다. 각 서비스는 `application.yml`에 명시한 JDBC URL(`localhost:15432` / `15433`)로 접속한다. `spring-boot-docker-compose` 자동 기동은 **쓰지 않는다**. (Codex 리뷰 반영)
  - 이유: 모듈별 compose와 루트 compose를 같이 쓰면 compose 프로젝트명이 달라 DB가 중복 생성되고 포트가 충돌한다. 한 compose에 Postgres가 둘이면 Boot의 ServiceConnection이 모호해진다. `include:`는 Compose 2.20.3 이상이 필요하다. 실행 경로를 하나로 줄이는 것이 실격 위험을 가장 줄인다.
  - DB가 떠 있지 않으면 앱이 바로 실패하고, 로그에 "`docker compose up -d`를 먼저 실행하라"는 안내가 보이게 한다(Flyway/DataSource 연결 실패 메시지 + README 안내). [판단]
- 실행 절차(README):
  1. `docker compose up -d`
  2. `./gradlew :group-service:bootRun`
  3. `./gradlew :member-service:bootRun`
  4. curl 예시로 확인
- **smoke 스크립트** `scripts/smoke.sh`: 1~3단계를 수행하고 4개 API를 호출해 상태 코드와 핵심 필드를 확인한 뒤 정리한다. 제출 전에 clone한 깨끗한 디렉터리에서 실행해 "clone → 빌드 → 실행"을 검증한다. (Codex 리뷰 반영)
- **금지 문자열 검사**: 제출 전에 저장소 전체(코드, 문서, `ai-logs/`)에서 회사명 패턴을 검사한다. 검사 패턴도 리터럴로 쓰지 않는다(escape 표기). `.claude/hooks/save-transcript.sh`도 같은 방식으로 이미 수정했다. (Codex 리뷰 반영)

## 12. 테스트 [결정: 시나리오]

DB가 필요한 테스트는 Testcontainers PostgreSQL + `@ServiceConnection`으로 실행한다. 그룹 서비스는 가짜 HTTP 서버로 대체한다. [미검증: WireMock의 Boot 4/Jetty 12 호환성. 안 되면 OkHttp MockWebServer]

| # | 시나리오 | 위치 | 검증 |
|---|---|---|---|
| 1 | 동명이인 + 동일 가입시각이 있어도 offset과 cursor 모두 끝까지 순회했을 때 누락/중복 없음 (asc, desc 각각) | member persistence 통합 | 전체 ID 집합 동일, 순서 = 정렬 키 |
| 2 | 그룹 서비스 무응답이어도 요청이 끝남: **느린 헤더**, **헤더 후 느린 본문**, **연결 지연** 각각 | member 통합 (가짜 서버 지연) | 503 `READ_TIMEOUT`/`CONNECT_TIMEOUT` 또는 `RETRY_BUDGET_EXCEEDED`, 경과 시간 < 3초 |
| 3 | 잘못된 요청은 외부 호출 전에 차단 (`page`+`cursor`, size 범위, 깨진 cursor, 지원하지 않는 sort, ID 개수 초과) | member/group web | 400 ProblemDetail, **가짜 그룹 서버 호출 수 0** |
| 4 | 동시 호출 제한이 재시도 바깥에 있음 | member 통합 (상한 1, 느린 응답) | 두 번째 요청이 즉시 503 `CONCURRENCY_REJECTED`, 추가 호출 없음 |
| 5 | 재시도 예산이 전체 시간을 상한 안에 묶음 | Gateway 단위/통합 | 1차 타임아웃 후 총 소요 < 3초, 시도 횟수 ≤ 2 |
| 6 | 서킷 open 시 재시도 없이 즉시 실패 | Gateway 통합 | 503 `CIRCUIT_OPEN` + `Retry-After`, 가짜 서버 추가 호출 0 |
| 7 | 추적/로그 | member 통합 + OutputCapture | 실패 로그에 traceId와 stage가 있음, 가짜 서버가 받은 `traceparent`의 traceId = 응답 `traceId` |
| 8 | 성공 경로 | 각 서비스 | 4개 API가 과제 시그니처 JSON과 일치, 그룹 없는 멤버는 `null` |
| 10 | 서킷 복구: half-open 상태에서 "그룹 없음" 404만 와도 CLOSED로 복귀 | Gateway 통합 | 상태 전이 OPEN → HALF_OPEN → CLOSED |
| 11 | upstream 계약 오류: code 없는 404, 깨진 JSON, 알 수 없는 enum | member 통합 | 503 `UPSTREAM_CONTRACT_ERROR`, HTTP 시도 1회(재시도 없음) |
| 12 | DB 커넥션 비점유: 그룹 호출이 느린 동안 DB 커넥션이 반환됨(OSIV off 검증 포함) | member 통합 | 그룹 호출 지연 중 Hikari active connection = 0 |
| 9 | 계약 일치 | group-service web | 컨트롤러가 `GroupApi`를 구현, 실제 MVC 매핑으로 `GroupApi` 클라이언트 프록시 호출 성공(MockMvc/RestTestClient) |

## 13. builder에게 맡기는 것
- 클래스/파일 세부 이름, Kotlin 코드 스타일(공식 Kotlin 컨벤션 + `.editorconfig`), 린터 도입 여부
- OpenAPI 어노테이션 위치(§10)
- 로그 메시지 문구, 시드 데이터 구체 값
- 테스트 클래스 구성과 테스트 슬라이스 선택
- README 문장 (단, 필수 섹션: 실행 절차, 모듈 구조와 "직접 참조 금지" 근거, CQRS 의도, 장애 처리와 시간 예산 계산, 모니터링/알림 기준, 해석한 요구사항 목록, AI 활용/로깅)
