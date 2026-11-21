package dev.task.member.infrastructure.group

import dev.task.member.TestcontainersConfig
import dev.task.member.api.Permission
import dev.task.member.api.Pricing
import dev.task.member.infrastructure.group.GroupCallStage.CIRCUIT_OPEN
import dev.task.member.infrastructure.group.GroupCallStage.CONCURRENCY_REJECTED
import dev.task.member.infrastructure.group.GroupCallStage.CONNECT_TIMEOUT
import dev.task.member.infrastructure.group.GroupCallStage.READ_TIMEOUT
import dev.task.member.infrastructure.group.GroupCallStage.RETRY_BUDGET_EXCEEDED
import dev.task.member.infrastructure.group.GroupCallStage.UPSTREAM_5XX
import dev.task.member.infrastructure.group.GroupCallStage.UPSTREAM_CONTRACT_ERROR
import dev.task.member.query.port.MemberGroupDetail
import dev.task.member.query.port.MemberGroupSummary
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.MeterRegistry
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.ResourceAccessException
import java.net.ServerSocket
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DETAIL =
    """{"memberId":"a000000001","groupId":"g000000001","groupName":"Alpha","registeredDate":"2026-01-02 03:04","pricing":"Free","permission":"Admin"}"""

private fun json(code: Int, body: String, type: String = "application/json") =
    MockResponse.Builder().code(code).addHeader("Content-Type", type).body(body)

private fun problem(code: Int, problemCode: String?) = json(
    code,
    """{"type":"about:blank","title":"x","status":$code${problemCode?.let { ""","code":"$it"""" } ?: ""},"traceId":null}""",
    "application/problem+json",
)

private fun always(response: MockResponse) = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest) = response
}

/** Runs [block] expecting a gateway failure; returns the exception and elapsed millis. */
private fun failureOf(block: () -> Any?): Pair<GroupServiceException, Long> {
    val t0 = System.nanoTime()
    val e = assertFailsWith<GroupServiceException> { block() }
    return e to (System.nanoTime() - t0) / 1_000_000
}

private fun within4s(block: () -> Unit) = assertTimeoutPreemptively(Duration.ofSeconds(4), block)

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfig::class)
abstract class MockGroupServerSupport {
    companion object {
        @JvmStatic protected val server = MockWebServer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun groupServer(registry: DynamicPropertyRegistry) {
            registry.add("spring.http.serviceclient.group.base-url") { server.url("/").toString().removeSuffix("/") }
        }
    }

    @Autowired protected lateinit var gateway: GroupServiceGateway
    @Autowired protected lateinit var meters: MeterRegistry
    private var baseline = 0
    protected val requests get() = server.requestCount - baseline
    protected val breaker: CircuitBreaker get() = gateway.circuitBreaker

    @BeforeEach
    fun resetServerAndBreaker() {
        server.dispatcher = QueueDispatcher()
        breaker.reset()
        rebase()
    }

    protected fun rebase() {
        baseline = server.requestCount
    }

    protected fun enqueue(vararg responses: MockResponse.Builder) = responses.forEach { server.enqueue(it.build()) }

    /** 5 calls x 2 attempts of 503 = 10 recorded failures = minimumNumberOfCalls at 100% -> OPEN. */
    protected fun openBreaker() {
        server.dispatcher = always(json(503, "{}").build())
        repeat(5) { assertFailsWith<GroupServiceException> { gateway.findDetail("a000000001") } }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }
}

@ExtendWith(OutputCaptureExtension::class)
class GroupServiceGatewayTest : MockGroupServerSupport() {

    @Test
    fun slowHeadersFailWithinBudget() = within4s {
        server.dispatcher = always(json(200, DETAIL).headersDelay(5, SECONDS).build())
        val (e, ms) = failureOf { gateway.findDetail("a000000001") }
        assertEquals(READ_TIMEOUT, e.stage) // retries exhausted, none skipped -> not RETRY_BUDGET_EXCEEDED
        assertEquals(2, e.attempts)
        assertTrue(ms < 3000, "elapsed=$ms")
        assertEquals(2, requests)
    }

    @Test
    fun slowBodyFailsWithinBudget() = within4s {
        server.dispatcher = always(json(200, DETAIL).bodyDelay(5, SECONDS).build())
        val (e, ms) = failureOf { gateway.findDetail("a000000001") }
        assertEquals(READ_TIMEOUT, e.stage) // retries exhausted, none skipped -> not RETRY_BUDGET_EXCEEDED
        assertEquals(2, e.attempts)
        assertTrue(ms < 3000, "elapsed=$ms")
        assertEquals(2, requests)
        // each slow-body attempt is a recorded (timeout) failure, not an unrecorded contract error
        assertEquals(e.attempts, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun fivexxIsRetriedOnceThenUpstream5xx(output: CapturedOutput) = within4s {
        val failuresBefore = meters.counter("group.call.failures", "stage", "UPSTREAM_5XX").count()
        enqueue(json(503, "{}"), json(503, "{}"))
        val (e, _) = failureOf { gateway.findDetail("a000000001") }
        assertEquals(UPSTREAM_5XX, e.stage)
        assertEquals(2, e.attempts)
        assertEquals(2, requests)
        assertEquals(failuresBefore + 1, meters.counter("group.call.failures", "stage", "UPSTREAM_5XX").count())
        assertTrue(meters.timer("group.call.duration", "outcome", "UPSTREAM_5XX").count() >= 1)
        assertTrue(
            Regex("""group call failed op=detail stage=UPSTREAM_5XX attempts=2 elapsedMs=\d+ cause=\S*HttpServerErrorException""")
                .containsMatchIn(output.out),
            output.out,
        )
    }

    @Test
    fun retrySucceedsAfterOne5xx() = within4s {
        val successBefore = meters.timer("group.call.duration", "outcome", "success").count()
        enqueue(json(503, "{}"), json(200, DETAIL))
        assertEquals(
            MemberGroupDetail("g000000001", "Alpha", LocalDateTime.of(2026, 1, 2, 3, 4), Pricing.Free, Permission.Admin),
            gateway.findDetail("a000000001"),
        )
        assertEquals(2, requests)
        assertEquals(successBefore + 1, meters.timer("group.call.duration", "outcome", "success").count())
    }

    @Test
    fun openCircuitFailsFastWithoutRetry() = within4s {
        openBreaker()
        rebase()
        val (e, _) = failureOf { gateway.findDetail("a000000001") }
        assertEquals(CIRCUIT_OPEN, e.stage)
        assertEquals(0, e.attempts)
        assertEquals(0, requests)
    }

    @ParameterizedTest
    @ValueSource(strings = ["notFoundWithoutCode", "badRequest", "brokenJson", "unknownEnum", "emptyBody"])
    fun contractErrorsAreNotRetried(case: String) = within4s {
        enqueue(
            when (case) {
                "notFoundWithoutCode" -> problem(404, null)
                "badRequest" -> problem(400, "INVALID_REQUEST")
                "brokenJson" -> json(200, """{"memberId":""")
                "unknownEnum" -> json(200, DETAIL.replace("Admin", "Owner"))
                else -> json(200, "")
            },
        )
        val (e, _) = failureOf { gateway.findDetail("a000000001") }
        assertEquals(UPSTREAM_CONTRACT_ERROR, e.stage)
        assertEquals(1, e.attempts)
        assertEquals(1, requests)
    }

    @Test
    fun unrequestedMembersInSummaryAreIgnored() = within4s {
        enqueue(
            json(
                200,
                """{"groups":[
                  {"groupId":"g000000001","groupName":"Alpha","totalMemberCount":3,
                   "members":[{"memberId":"a000000001","permission":"Admin"},{"memberId":"x000000009","permission":"Viewer"}]},
                  {"groupId":"g000000002","groupName":"Beta","totalMemberCount":1,
                   "members":[{"memberId":"b000000002","permission":"Editor"}]}]}""",
            ),
        )
        val result = gateway.findSummaries(listOf("a000000001", "a000000001", "b000000002"))
        assertEquals(
            mapOf(
                "a000000001" to MemberGroupSummary("Alpha", Permission.Admin),
                "b000000002" to MemberGroupSummary("Beta", Permission.Editor),
            ),
            result,
        )
        assertEquals(1, requests)
    }

    @Test
    fun notFoundWithCodeReturnsNullDetail() = within4s {
        enqueue(problem(404, "MEMBER_GROUP_NOT_FOUND"))
        assertNull(gateway.findDetail("a000000001"))
        assertEquals(1, requests)
    }
}

@TestPropertySource(
    properties = [
        "app.group-client.concurrency-limit=1",
        "app.group-client.circuit-breaker.wait-duration-in-open-state=200ms",
        "app.group-client.retry.timeout=500ms", // below one read-timeout (1s): a timed-out first attempt skips the retry
    ],
)
class TunedGroupServiceGatewayTest : MockGroupServerSupport() {

    @Test
    fun concurrencyLimitRejectsImmediately() = within4s {
        val arrived = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                return json(200, DETAIL).headersDelay(500, MILLISECONDS).build()
            }
        }
        val first = CompletableFuture.supplyAsync { gateway.findDetail("a000000001") }
        assertTrue(arrived.await(2, SECONDS))
        val (e, ms) = failureOf { gateway.findDetail("b000000002") }
        assertEquals(CONCURRENCY_REJECTED, e.stage)
        assertEquals(0, e.attempts)
        assertTrue(ms < 500, "elapsed=$ms") // spec: "immediate"
        assertEquals(1, requests)
        assertNotNull(first.get())
    }

    @Test
    fun retrySkippedWhenBudgetSpentIsRetryBudgetExceeded() = within4s {
        server.dispatcher = always(json(200, DETAIL).headersDelay(5, SECONDS).build())
        val (e, ms) = failureOf { gateway.findDetail("a000000001") }
        assertEquals(RETRY_BUDGET_EXCEEDED, e.stage)
        assertEquals(1, e.attempts)
        assertEquals(1, requests)
        assertTrue(e.cause is ResourceAccessException, "cause=${e.cause}")
        assertTrue(ms < 3000, "elapsed=$ms") // ~1.0s expected; spec total budget bound
    }

    @Test
    fun halfOpenRecoversOnNotFoundOnly() = within4s {
        openBreaker()
        Thread.sleep(300) // waitDurationInOpenState=200ms; R4j moves to HALF_OPEN lazily on the next call
        server.dispatcher = always(problem(404, "MEMBER_GROUP_NOT_FOUND").build())
        assertNull(gateway.findDetail("a000000001"))
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)
        repeat(2) { assertNull(gateway.findDetail("a000000001")) }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfig::class)
class RefusedGroupServiceGatewayTest {
    companion object {
        private val closedPort = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun closedPort(registry: DynamicPropertyRegistry) {
            registry.add("spring.http.serviceclient.group.base-url") { "http://127.0.0.1:$closedPort" }
        }
    }

    @Autowired lateinit var gateway: GroupServiceGateway

    @Test
    fun connectionRefusedIsConnectTimeoutStage() = within4s {
        val (e, _) = failureOf { gateway.findDetail("a000000001") }
        assertEquals(CONNECT_TIMEOUT, e.stage)
        assertEquals(2, e.attempts)
    }
}
