# 멤버 조회 / 그룹 조회 서비스

메시지 브로커 없이 물리적으로 분리된 두 서비스다.

- **member-service**: 외부 공개. 멤버 목록과 상세를 제공하고, 그룹 정보는 group-service를 HTTP로 호출해 합친다.
- **group-service**: 내부 전용. 멤버 ID 기준으로 그룹 요약과 상세를 제공한다.

Kotlin 2.3.21 · JDK 25 · Spring Boot 4.1.3 · Gradle 9.3.1 · PostgreSQL 17 (서비스별 DB) · Flyway · Spring Data JPA · Resilience4j 2.4.0(core) · Micrometer Tracing(Brave) + Prometheus · springdoc-openapi 3.2.0

## 1. 사전 요구사항

| 항목 | 비고 |
|---|---|
| JDK 25 | Gradle toolchain이 JDK 25를 찾고, 없으면 자동으로 내려받는다(foojay). Gradle 자체 실행에는 JDK 17 이상이 필요하다 |
| Docker + Compose v2 | 실행(`docker compose`)과 테스트(Testcontainers) 모두 Docker를 쓴다. Docker 없이 도는 대체 경로(H2 등)는 두지 않았다 |
| `curl`, `jq` | `scripts/smoke.sh`와 아래 예시에서 사용한다 |

사용 포트는 8080, 8081, 9080, 9081, 15432, 15433이다. 실행 전에 비어 있어야 한다.

## 2. 실행

### 한 번에 검증: `scripts/smoke.sh`

```bash
scripts/smoke.sh
```

스크립트는 다음 순서로 동작한다.

1. DB 두 개를 띄운다(`docker compose up -d --wait`).
2. `./gradlew --parallel :group-service:bootRun :member-service:bootRun`으로 두 서비스를 실행한다. Gradle을 한 번만 호출하는 이유는, `./gradlew`를 두 번 동시에 실행하면 같은 프로젝트의 Gradle 락을 두고 경합하기 때문이다. 이후 관리 포트의 `/actuator/health/readiness`를 최대 90초 동안 polling한다.
3. 실제 HTTP로 4가지를 확인한다. 목록 `size=5` → 5건, cursor 첫 페이지 → `hasNext`, 그룹 없는 멤버 상세 → `group == null`, 잘못된 ID → 400.
4. 종료 시(성공·실패 무관) trap이 Gradle을 종료하고 앱 JVM이 내려간 것을 확인한다. **DB는 스크립트가 직접 띄운 경우에만** `docker compose down -v`로 정리한다. 이미 떠 있던 DB 컨테이너는 그대로 둔다.

앱 로그는 임시 디렉터리에 저장되고 경로가 출력된다. 실행 시작 전에 앱 포트(8080/8081/9080/9081)가 사용 중이면 바로 실패한다.

### 직접 실행

```bash
docker compose up -d                  # member-db :15432, group-db :15433
./gradlew :group-service:bootRun      # 터미널 1
./gradlew :member-service:bootRun     # 터미널 2
```

- 두 서비스의 기동 순서는 상관없다. member의 readiness는 자기 DB만 본다.
- DB가 떠 있지 않으면 앱은 DataSource/Flyway 연결 오류로 바로 종료된다. 먼저 `docker compose up -d`를 실행한다.
- 스키마와 샘플 데이터(멤버 50명, 그룹 5개, 그룹 없는 멤버 5명)는 Flyway가 기동 시 적용한다.
- `prod` 프로필(`--spring.profiles.active=prod`)은 시드 없이 스키마만 적용하고, 로그를 ECS JSON 형식으로 출력한다.

### 포트

| 대상 | 주소 | 바인딩 |
|---|---|---|
| member-service API | `http://localhost:8080` | 모든 인터페이스 (외부 공개) |
| group-service API | `http://127.0.0.1:8081` | loopback 전용 (`server.address`) |
| member / group 관리 포트 (actuator) | `127.0.0.1:9080` / `127.0.0.1:9081` | loopback 전용 |
| member-db / group-db | `localhost:15432` / `localhost:15433` | 로컬 Postgres 기본 포트(5432)와 겹치지 않게 함 |

### curl 예시

시드 ID: `27ysni0g1i`는 그룹 소속(Alpha Team, Admin)이고, `pq12td4qds`는 그룹이 없다.

```bash
# 목록: offset 방식(기본)
curl -s 'http://localhost:8080/api/members?size=3&page=0&sort=name,asc' | jq
# → {"members":[{"id":"…","name":"…","group":"Beta Squad","permission":"Viewer"},…],
#    "page":{"number":0,"size":3,"totalElements":50,"totalPages":17}}

# 목록: cursor 방식. 첫 페이지는 cursor 없이 호출하고, 다음 페이지는 응답의 cursor.next를 넘긴다
next=$(curl -s 'http://localhost:8080/api/members?size=5&pagination=cursor' | jq -r .cursor.next)
curl -s "http://localhost:8080/api/members?size=5&pagination=cursor&cursor=$next" | jq

# 상세
curl -s http://localhost:8080/api/member/27ysni0g1i | jq
# → {"id":"27ysni0g1i","name":"김민수","email":"member1@example.com","address":"…","registeredDate":"2025-01-01 09:00",
#    "group":{"id":"g1a2b3c4d5","name":"Alpha Team","registeredDate":"2025-06-01 12:00","pricing":"Free","permission":"Admin"}}
curl -s http://localhost:8080/api/member/pq12td4qds | jq '.group'     # → null

# 오류는 모두 ProblemDetail(application/problem+json) + code + traceId
curl -s http://localhost:8080/api/member/ABC | jq
# → {"status":400,"title":"Bad Request","detail":"invalid member id","code":"INVALID_REQUEST","traceId":"6b00…"}

# group-service (내부 전용이라 loopback에서만 접근 가능)
curl -s 'http://127.0.0.1:8081/api/groups/summary?memberIds=27ysni0g1i,itn3xvk9tf,pq12td4qds' | jq
curl -s 'http://127.0.0.1:8081/api/group?memberId=27ysni0g1i' | jq
```

### OpenAPI

- member: `http://localhost:8080/swagger-ui.html`, `http://localhost:8080/v3/api-docs`
- group: `http://127.0.0.1:8081/swagger-ui.html`, `http://127.0.0.1:8081/v3/api-docs`

### 테스트

```bash
./gradlew build      # 전체 단위 + 통합 테스트. Testcontainers가 PostgreSQL을 띄우므로 Docker가 필요하다
./gradlew :member-service:test --tests 'dev.task.member.infrastructure.group.GroupServiceGatewayTest'
```

그룹 서비스는 테스트에서 OkHttp MockWebServer로 대체한다. 지연(느린 헤더, 느린 본문), 5xx, 깨진 JSON, code 없는 404, 연결 거부를 재현한다.

## 3. API 요약

| API | 규칙 |
|---|---|
| `GET /api/members` | `size` 1..100(기본 20), `sort` = `name,asc`\|`name,desc`(기본 asc), `pagination` = `offset`(기본)\|`cursor`, `page`(offset 전용, 0 이상), `cursor`(cursor 전용). 방식에 맞지 않는 파라미터는 400이다. 응답에는 사용한 방식에 해당하는 `page` 또는 `cursor` 블록 하나만 들어간다 |
| `GET /api/member/{memberId}` | ID는 `^[a-z0-9]{10}$`. 멤버가 없으면 404 `MEMBER_NOT_FOUND`이고 그룹 서비스는 호출하지 않는다. 그룹이 없으면 `"group": null` |
| `GET /api/groups/summary?memberIds=a,b` | 1..100개, 중복 제거. `totalMemberCount`는 그룹 전체 멤버 수, `members`는 요청한 ID 중 그 그룹 소속인 멤버만. 그룹이 없는 ID는 응답에 나오지 않는다 |
| `GET /api/group?memberId=x` | 소속이 없으면 404 `MEMBER_GROUP_NOT_FOUND` |

- 정렬 키는 `name → registeredAt → id`이고 세 컬럼 모두 요청 방향을 따른다. 동명이인에 가입 시각까지 같아도 순서가 결정적이다.
- 목록 한 페이지당 그룹 서비스는 **배치 1건**(`/api/groups/summary`)으로 호출한다. 재시도를 포함해도 HTTP 시도는 최대 2회다. 결과가 0건인 페이지는 그룹 서비스를 호출하지 않는다.
- 날짜는 `"yyyy-MM-dd HH:mm"` 형식이다. DB에는 `timestamptz`로 저장하고 `app.time-zone`(기본 `Asia/Seoul`)으로 렌더링한다.

## 4. 모듈 구조와 "직접 참조 금지"

```
root
├── group-api        계약: DTO + GroupApi(@HttpExchange). group-service 팀이 소유하는 모듈
├── group-service    GroupApi를 @RestController로 구현. group DB 사용. 내부 전용
├── member-api       계약: DTO + MemberApi(@HttpExchange). 프런트/타 서비스 공유용
└── member-service   MemberApi 구현. member DB 사용. GroupApi 인터페이스로 HTTP 클라이언트 생성
```

| 모듈 | 의존 |
|---|---|
| `group-api`, `member-api` | `spring-web`(어노테이션), `jackson-annotations`만. Spring Boot에 의존하지 않는다 |
| `group-service` | `group-api` |
| `member-service` | `member-api`, `group-api` |

- **`*-service` 모듈끼리는 main/test 어느 scope로도 의존하지 않는다.** 서비스 사이에서 공유하는 것은 계약 모듈뿐이고, 호출은 네트워크로만 한다. 그래서 member-service 코드는 group-service의 엔티티, 리포지토리, DB를 import할 방법 자체가 없다. "직접 참조 금지"를 규칙이 아니라 빌드 그래프로 강제한 것이다.
- 서버와 클라이언트가 **같은 `@HttpExchange` 인터페이스**를 쓴다. group-service의 컨트롤러가 `GroupApi`를 구현하고, member-service는 Boot 4 HTTP Service Client(`@ImportHttpServices(group = "group", types = [GroupApi::class])`)로 같은 인터페이스의 프록시를 만든다. 경로나 파라미터가 바뀌면 양쪽이 컴파일 단계에서 함께 깨진다.
- `member-api`는 `group-api`에도 의존하지 않는다. `Pricing`/`Permission` enum을 따로 가지고, member-service에서 매핑한다. 그룹 서비스의 내부 계약이 바뀌어도 member의 공개 계약은 바뀌지 않아야 하기 때문이다.
- 실무라면 `*-api`는 사내 저장소에 배포하는 라이브러리다. 과제에서는 Gradle project 의존으로 대신했다.
- `common` 모듈과 convention plugin은 두지 않았다. 공통 설정은 루트 `build.gradle.kts`와 `gradle/libs.versions.toml`에 있다.

## 5. CQRS 의도

과제 API는 전부 조회다. 그래서 **command 쪽은 만들지 않았다.** 쓰지 않는 `command/` 패키지나 인터페이스를 미리 만드는 대신, 읽기 경로만 구조로 분리했다.

```
member/
├── web/                         MemberController(MemberApi 구현), ProblemDetail 매핑
├── query/                       GetMembersQuery(검증된 조회 조건, Pagination sealed 타입) + Handler, 조회 모델
├── query/port/GroupReader       외부 서비스 포트. 구현이 실제로 바뀔 수 있는 경계에만 인터페이스를 둔다
└── infrastructure/
    ├── persistence/             MemberEntity(테이블 매핑 전용), MemberJpaRepository(DTO projection),
    │                            MemberReadRepository(@Transactional(readOnly = true) 경계)
    └── group/                   GroupServiceGateway(GroupReader 구현, 탄력성 조합)
```

- 컨트롤러는 Query를 만들어 Handler에 넘긴다. Handler는 그룹을 `GroupReader` 포트로만 조회한다. 그룹 조회 방식이 HTTP에서 캐시나 이벤트 프로젝션으로 바뀌어도 Handler 코드는 그대로다.
- 조회 모델은 엔티티가 아니라 **JPQL constructor expression(DTO projection)**으로 바로 만든다. 엔티티를 로딩하지 않으므로 dirty checking이나 지연 로딩이 끼어들지 않는다.
- **트랜잭션 범위**: Handler에는 트랜잭션을 걸지 않는다. DB 조회(목록 + count)는 `MemberReadRepository`의 read-only 트랜잭션 안에서 끝나고, 커넥션을 반환한 **뒤에** 그룹 서비스를 호출한다. 그룹 호출이 느리거나 재시도되는 동안 DB 커넥션을 붙잡지 않는다. `spring.jpa.open-in-view=false`가 필수인 이유도 이것이다. 테스트로 검증했다: 그룹 호출이 지연되는 동안 Hikari active connection이 0이다.
- 저장소처럼 구현이 하나뿐이고 바뀔 이유가 없는 곳에는 인터페이스를 두지 않았다.

**쓰기가 생기면 붙는 위치**

- 각 서비스에 `command/` 패키지(Command + Handler, `@Transactional`)를 추가하고, 엔티티를 쓰기 모델로 키운다. 지금 엔티티는 매핑만 담당한다. 읽기 경로(`query/`, projection)는 건드리지 않는다.
- 그룹 소속 변경이 잦아지면 group-service가 변경 이벤트를 발행하고(outbox), member-service는 이를 받아 읽기 전용 프로젝션 테이블을 둘 수 있다. 이때 `GroupReader`의 구현만 HTTP Gateway에서 프로젝션 조회로 바꾸면 된다. 지금은 브로커가 없다는 과제 조건 때문에 동기 HTTP를 쓴다.

## 6. 장애 처리

### 원칙

- "그룹 없음"은 장애가 아니다. 그룹 서비스가 정상 응답했는데 소속이 없는 경우이며, 200에 `group: null`로 응답한다.
- **그룹 서비스 장애는 503 + ProblemDetail**로 응답한다. 빈 배열이나 null로 숨기지 않는다.
- 응답 `code`, 로그 `stage`, 지표 태그 `stage`는 **같은 값**을 쓴다.

| code / stage | 원인 | 재시도 | 비고 |
|---|---|---|---|
| `CONCURRENCY_REJECTED` | 동시 호출 상한(50) 초과 | - | 그룹 서버를 호출하지 않음 |
| `CIRCUIT_OPEN` | 서킷 열림 | - | 그룹 서버를 호출하지 않음. `Retry-After: 10`(서킷 open 대기 시간) |
| `CONNECT_TIMEOUT` | 연결 타임아웃 / 연결 실패 | O | |
| `READ_TIMEOUT` | 응답 타임아웃 | O | |
| `UPSTREAM_5XX` | 그룹 서비스 5xx | 502/503/504만 | |
| `UPSTREAM_CONTRACT_ERROR` | 예상하지 못한 4xx(code 없는 404 포함), JSON 파싱 실패, 알 수 없는 enum, 빈 본문 | X | 사용자 입력 오류(400)와 구분한다 |
| `RETRY_BUDGET_EXCEEDED` | 시간 예산 때문에 재시도를 건너뛰고 실패 | - | 마지막 원인 예외 클래스를 로그에 남김 |

member-service는 위 code를 모두 **503**으로 응답한다. 변환은 `@RestControllerAdvice` 한 곳에서 한다.

### 조합 순서 (어노테이션 대신 코드로 명시)

```
① Semaphore.tryAcquire()   실패 → CONCURRENCY_REJECTED   (가장 바깥: 재시도 내내 슬롯 1개 점유)
② RetryTemplate            maxRetries=1, delay=100ms, timeout=1.5s   (Spring Framework 7 core retry)
③ CircuitBreaker           시도마다 성공/실패 기록   (window 20, 최소 10회, 실패율 50%, open 10s, half-open 3회)
④ GroupApi (JDK HttpClient) connect 0.5s / read 1s
finally: release()
```

- `@ConcurrencyLimit`과 `@Retryable`은 order가 같아서 실제 적용 순서가 bean 정의 순서에 따라 정해진다. 문서화되지 않은 동작이므로 쓰지 않고 `GroupServiceGateway`에서 순서를 코드로 고정했다.
- HTTP 구현은 `spring.http.clients.imperative.factory=jdk`로 고정한다. 클래스패스에 따라 구현이 바뀌면 타임아웃의 의미도 바뀌기 때문이다.
- 그룹 상세의 404 + `code=MEMBER_GROUP_NOT_FOUND`는 **서킷 안쪽에서** `null`로 바꿔 성공으로 기록한다. Resilience4j의 `ignoreExceptions`는 쓰지 않았다. 무시된 호출은 성공 표본도 쌓지 않아서, half-open 상태에서 이 404만 계속 오면 서킷이 CLOSED로 돌아가지 못한다. 이 복구 경로는 테스트로 검증했다.
- 모든 수치는 `app.group-client.*`(동시 상한, 재시도, 서킷)와 `spring.http.serviceclient.group.*`(base-url, 타임아웃)으로 바꿀 수 있다.

### 분류 규칙

- 원인 체인에 `CallNotPermittedException` → `CIRCUIT_OPEN`, `HttpConnectTimeoutException`/`ConnectException` → `CONNECT_TIMEOUT`, `HttpTimeoutException`/`SocketTimeoutException` → `READ_TIMEOUT`, `HttpServerErrorException` → `UPSTREAM_5XX`.
- **그 밖의 `IOException`은 `READ_TIMEOUT`으로 분류한다.** 본문 수신 중에 read timeout이 나면 Spring의 JDK 클라이언트가 스트림을 닫아서 `HttpTimeoutException`이 아니라 일반 `IOException("closed")`로 올라온다. 연결 reset이나 EOF도 여기에 들어간다. 모두 재시도할 가치가 있는 일시 장애로 본다.
- 빈 본문이나 역직렬화할 수 없는 본문 → `UPSTREAM_CONTRACT_ERROR`.
- 그룹 상세 404는 `code=MEMBER_GROUP_NOT_FOUND`일 때만 "그룹 없음"(`null`)이다. 그 밖의 404(예: base-url 오설정)는 `UPSTREAM_CONTRACT_ERROR`다.
- **`RETRY_BUDGET_EXCEEDED`는 예산 때문에 재시도를 실제로 건너뛴 경우에만** 쓴다. 즉 timeout 콜백이 발생했고 시도 횟수가 `maxRetries` 이하일 때다. Spring `RetryTemplate`은 재시도 횟수를 다 썼는지보다 timeout을 먼저 검사하기 때문에, 두 번 다 시도하고 실패한 경우에도 timeout 콜백이 발생한다. 이 경우는 원래 원인(예: `READ_TIMEOUT`)으로 보고한다.

### 시간 예산 계산

- HTTP 시도 1회 상한 = connect 0.5s + read 1.0s = **1.5s**
- 재시도는 `경과 시간 + 100ms(delay) < 1.5s(timeout)`일 때만 시작한다. 진행 중인 시도를 끊지는 않는다.
- 따라서 최악의 경우는 1차 시도가 1.4s 직전에 실패하고, 재시도가 1.5s를 다 쓰는 경우다. 합계 **약 3초 미만**이다.
- 이 계산은 "connect + read가 시도 1회의 상한"이라는 전제에 기댄다. 이 전제가 **느린 헤더**와 **헤더 이후 느린 본문** 모두에서 성립하는지 테스트로 확인했다. 그래서 시도 단위 hard deadline은 추가하지 않았다.

| 측정 (테스트) | 결과 |
|---|---|
| 느린 헤더 (5s 지연) | `READ_TIMEOUT`, 2회 시도, 약 2.1s (1.0 + 0.1 + 1.0) |
| 헤더 후 느린 본문 | `READ_TIMEOUT`, 2회 시도, 약 2.2s |
| 예산이 남지 않아 재시도 생략 (`retry.timeout=500ms` 설정) | `RETRY_BUDGET_EXCEEDED`, 1회 시도, 약 1.0s |
| 서킷 open | `CIRCUIT_OPEN`, 그룹 서버 호출 0회, 즉시 실패 |
| 동시 상한 초과 (상한 1) | `CONCURRENCY_REJECTED`, 100ms 미만 |
| 연결 거부 | `CONNECT_TIMEOUT`, 2회 시도 |

### 잘못된 요청은 외부 호출 전에 차단

`page`+`cursor` 동시 지정, `size` 범위 밖, 깨진 cursor, cursor와 sort의 방향 불일치, 지원하지 않는 sort, ID 형식 오류, `page*size`가 `Int` 범위를 넘는 페이지는 모두 400 `INVALID_REQUEST`다. 테스트에서는 이때 가짜 그룹 서버의 호출 수가 0인지 확인한다.

## 7. 관측성

### 추적과 로그

- Micrometer Tracing(Brave)으로 W3C `traceparent`를 member → group으로 전파한다. HTTP Service Client 프록시도 Boot가 구성한 RestClient를 쓰므로 자동 계측된다. 테스트에서 가짜 그룹 서버가 받은 `traceparent`의 traceId가 member 응답의 `traceId`와 같은지 확인한다.
- **모든 오류 ProblemDetail에 `code`와 `traceId`가 있다.** 프레임워크 오류도 같다: 없는 경로 404 `NOT_FOUND`, 405 `METHOD_NOT_ALLOWED`, 그 밖의 4xx `INVALID_REQUEST`, 예상하지 못한 예외 500 `INTERNAL_ERROR`(내부 정보는 응답에 넣지 않음). 두 서비스 모두 해당한다.
- 로그 패턴에 `[traceId,spanId]`를 넣었다. 그룹 호출이 실패하면 한 줄로 남긴다: `group call failed op=… stage=… attempts=… elapsedMs=… cause=…`.
- trace는 외부로 export하지 않는다. exporter 의존성이 클래스패스에 없어서 보낼 곳이 없다. `management.tracing.export.enabled: false`는 **일부러 설정하지 않았다.** Boot 4.1.3에서는 이 값이 export뿐 아니라 Brave의 전파까지 꺼서 `traceparent`가 비어 나가는 것을 확인했기 때문이다.
- `prod` 프로필은 Boot 구조화 로그(ECS JSON)를 쓴다.

### 지표 (`http://127.0.0.1:9080/actuator/prometheus`)

관리 포트는 loopback 전용이며 `health`, `info`, `prometheus`만 노출한다. readiness에는 **자기 DB만** 포함한다. member의 readiness에 그룹 서비스 상태를 넣으면, 그룹 서비스 장애가 member 인스턴스 전체의 트래픽 차단으로 번진다.

| 지표 (Prometheus 이름) | 의미 |
|---|---|
| `http_server_requests_seconds_*{uri,status}` | 인바운드 요청 (자동) |
| `http_client_requests_seconds_*{uri,status}` | 그룹 서비스로 보낸 **HTTP 시도 1회** 단위 (자동, histogram) |
| `group_call_duration_seconds_*{outcome}` | 재시도를 포함한 **Gateway 호출 전체** 시간. `outcome` = `success` 또는 stage (histogram) |
| `group_call_failures_total{stage}` | 실패 stage별 건수 |
| `group_call_permits_in_use` | 동시 호출 슬롯 사용량 |
| `resilience4j_circuitbreaker_state{name="group",state}` 외 `resilience4j_circuitbreaker_*` | 서킷 상태, 호출 결과, 실패율 |

### 알림 기준 예시 (PromQL)

`job` 라벨은 Prometheus scrape 설정에서 붙는다고 가정했다.

```promql
# 1) member 503 비율 > 1% (5분)
  sum(rate(http_server_requests_seconds_count{job="member-service", uri=~"/api/member.*", status="503"}[5m]))
/ sum(rate(http_server_requests_seconds_count{job="member-service", uri=~"/api/member.*"}[5m]))
> 0.01

# 2) 서킷이 1분 이상 닫히지 않음 (for: 1m)
#    그룹 서비스가 계속 죽어 있으면 OPEN → HALF_OPEN → OPEN을 반복하므로 둘을 함께 본다
max(resilience4j_circuitbreaker_state{job="member-service", name="group", state=~"open|half_open"}) == 1

# 3) 그룹 HTTP 시도 p99가 read timeout(1s)에 근접 (5분)
histogram_quantile(0.99,
  sum by (le) (rate(http_client_requests_seconds_bucket{job="member-service", uri=~"/api/group.*"}[5m]))
) > 0.8
```

원인을 볼 때는 `sum by (stage) (rate(group_call_failures_total[5m]))`로 실패 stage 분포를 확인한다.

## 8. 해석한 요구사항과 판단 목록

과제 문서에 명시되지 않아 해석이 필요했던 부분과, 설계 중에 내린 판단이다.

**API / 데이터**

1. 멤버 상세는 path variable(`/api/member/{memberId}`), 그룹 상세는 query string(`/api/group?memberId=`)이다.
2. 페이징은 offset과 cursor를 모두 지원하고, 기본은 `offset`이다. cursor 방식의 첫 페이지로 들어가는 경로가 필요해서 `pagination` 파라미터를 따로 두었다. 방식과 맞지 않는 파라미터는 조용히 무시하지 않고 400으로 거절한다.
3. cursor는 마지막 행의 `(name, registeredAt, id, 방향)`을 base64url(JSON)로 인코딩한 불투명 문자열이다. `registeredAt`은 화면 표시값(분 단위)이 아니라 DB 원본 정밀도(epoch µs)로 담는다. 디코딩에 실패하거나 방향이 `sort`와 다르면 400이다.
4. keyset 조건은 HQL 튜플 비교 `(m.name, m.registeredAt, m.id) > (:name, :registeredAt, :id)`로 썼다. Hibernate 7.4 + PostgreSQL에서 그대로 동작하는 것을 확인해서, 대안으로 준비했던 Spring Data `Window`/`ScrollPosition`(OR로 풀린 조건)은 쓰지 않았다. 동명이인 + 동일 가입 시각 데이터로 asc/desc 각각 offset과 cursor 전체 순회 시 누락과 중복이 없음을 테스트했다.
5. 멤버 상세의 `group.registeredDate`는 "멤버가 그룹에 추가된 시각"이다. 그룹 상세 API가 주는 값을 그대로 쓴다. 그룹 생성일은 계약에 없으므로 이렇게 해석했다.
6. 그룹 요약의 `totalMemberCount`는 그룹 **전체** 멤버 수이고, `members`는 요청한 ID 중 그 그룹 소속인 멤버만 담는다. 그룹이 없는 ID는 오류가 아니라 응답에서 빠진다. `memberIds`는 1..100개(member 페이지 크기 상한과 같음)이고 중복은 제거한다.
7. 멤버 ID 형식(`^[a-z0-9]{10}$`)을 양쪽 서비스 입구에서 검증한다. 형식이 틀리면 DB나 그룹 서비스를 호출하지 않고 400을 준다.
8. 멤버가 없으면 404 `MEMBER_NOT_FOUND`이고, 이때 그룹 서비스는 호출하지 않는다.
9. DB는 서비스별로 분리했다(member-db, group-db). 두 시드의 멤버 ID 50개는 일치시켰고, 그중 5개는 그룹이 없다. 시드에는 동명이인 + 동일 가입 시각, 3가지 pricing, 3가지 permission을 포함했다.
10. Flyway 위치를 `db/migration`(스키마)과 `db/seed`(샘플)로 나눴다. 기본 프로필은 둘 다, `prod`는 스키마만 적용한다.
11. 날짜는 `"yyyy-MM-dd HH:mm"`, DB는 `timestamptz`, 렌더링 zone은 `app.time-zone`(기본 `Asia/Seoul`)이다.

**경계 / 배치**

12. "서로 다른 host"는 서로 다른 프로세스와 포트로 충족한다. member는 그룹 서비스를 `http://127.0.0.1:8081`로 호출한다.
13. 내부 전용 경계: group-service API와 두 관리 포트는 loopback(`127.0.0.1`)에만 바인딩한다. 외부 인터페이스에 바인딩하는 것은 member-service API뿐이다.
14. 실행 경로는 루트 `compose.yaml` 하나로 줄였다. `spring-boot-docker-compose` 자동 기동은 쓰지 않는다. 한 compose 파일에 Postgres가 둘이면 ServiceConnection이 모호해지고, 모듈별 compose를 함께 쓰면 DB가 중복 생성되기 때문이다.
15. 패키지 루트는 중립 이름 `dev.task.*`로 정했다.
16. OpenAPI 어노테이션은 계약 인터페이스가 아니라 **컨트롤러에** 두었다. 계약 모듈을 Spring Boot/springdoc 비의존으로 유지하기 위해서다.
17. `member-api`는 `group-api`를 의존하지 않고 enum을 따로 가진다(§4).

**장애 / 관측**

18. 그룹 서비스 장애는 숨기지 않고 모두 503이다. "그룹 없음"(code가 있는 404)만 정상 `null`이다.
19. `RETRY_BUDGET_EXCEEDED`는 재시도를 실제로 건너뛴 경우에만 쓴다(§6 분류 규칙). 재시도 횟수를 다 쓰고 실패한 느린 upstream은 `READ_TIMEOUT`이다.
20. 그 밖의 `IOException`은 `READ_TIMEOUT`(재시도 대상)으로 분류하고, 빈 본문이나 해석할 수 없는 본문은 `UPSTREAM_CONTRACT_ERROR`(재시도 안 함)로 분류한다.
21. 시도 단위 hard deadline은 추가하지 않았다. connect + read 상한이 느린 헤더와 느린 본문 모두에서 지켜지는 것을 측정으로 확인했기 때문이다.
22. 서킷 브레이커 기본값은 count 기반 window 20, 최소 10회, 실패율 50%, open 10초, half-open 3회다. 동시 상한은 50이다. 느린 호출은 타임아웃으로 실패 처리되므로 slow-call 규칙은 쓰지 않는다.
23. 공통 오류 규칙("모든 오류는 ProblemDetail + `code` + `traceId`")을 두 서비스의 프레임워크 오류(404/405/500)까지 적용했다.
24. `management.tracing.export.enabled: false`는 설정하지 않았다(§7). exporter가 없어 실제로 export되는 것은 없다.
25. Prometheus/Grafana 컨테이너와 대시보드는 범위 밖이다. 지표 노출과 알림 기준 예시까지만 제공한다.

**범위 밖**: 쓰기 API, 인증/인가, API Gateway, 서비스 디스커버리, 메시지 브로커, trace 수집 백엔드.

## 9. 알려진 한계

- **이름 정렬은 DB 기본 collation(`en_US.utf8`, glibc)을 따른다.** 그래서 한글 이름의 순서가 사전순과 다르게 보일 수 있다. 같은 collation으로 정렬과 keyset 비교를 하므로 페이징의 누락/중복은 없다. 사전순이 필요하면 `name` 컬럼과 인덱스에 ICU collation(예: `COLLATE "ko-x-icu"`)을 지정하는 마이그레이션을 추가한다.
- 연결 단계 지연(SYN에 응답이 없는 경우)은 별도 테스트가 없다. 연결 거부만 테스트했다. connect timeout(0.5s)은 설정으로 걸려 있다.
- 서킷 브레이커와 동시 호출 상한은 인스턴스별이다. 여러 인스턴스로 늘리면 전체 상한은 인스턴스 수에 비례한다.

## AI 활용 및 세션 로깅

- **사용 도구**: Claude Code (CLI), Codex CLI (설계 문서 교차 리뷰)
- **로깅 방식**: Claude Code hooks (`.claude/settings.json`) → `.claude/hooks/save-transcript.sh`
  - `Stop`, `SubagentStop`, `PreCompact`, `SessionEnd` 이벤트마다 Claude Code가 기록하는 세션 원본 트랜스크립트(JSONL)를 `ai-logs/claude-code/<session-id>.jsonl` 로 복사합니다.
  - 트랜스크립트에는 입력한 프롬프트, AI 응답, 에이전트가 실행한 명령과 파일 수정 내역(tool call/result)이 시간 순으로 모두 포함됩니다.
  - 서브에이전트 세션은 `ai-logs/claude-code/<session-id>-subagents/` 에 저장됩니다.
  - 이 프로젝트 디렉터리에서 실행된 Codex CLI 세션(`~/.codex/sessions/**/*.jsonl` 중 `cwd`가 프로젝트 루트인 것)도 같은 훅이 `ai-logs/codex/` 로 복사합니다.
- **마스킹**: 복사 시 API 키/토큰, 이메일, 로컬 사용자명, 개인 이름 등 민감 정보만 `***` 로 치환하며, 그 외 편집은 하지 않습니다.
- 요구 도구: `jq`, `perl`
