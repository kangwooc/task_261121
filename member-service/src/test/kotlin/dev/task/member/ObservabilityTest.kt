package dev.task.member

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["management.server.port=0"])
@AutoConfigureTracing
@AutoConfigureMetrics
@Import(TestcontainersConfig::class)
@ExtendWith(OutputCaptureExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ObservabilityTest {
    companion object {
        private val server = MockWebServer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.http.serviceclient.group.base-url") { server.url("/").toString().removeSuffix("/") }
        }
    }

    @LocalServerPort private var port = 0
    @LocalManagementPort private var mgmtPort = 0
    @Autowired private lateinit var gateway: dev.task.member.infrastructure.group.GroupServiceGateway
    private val mapper = JsonMapper.builder().build()
    private val traceparent = Regex("^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$")

    private val client by lazy { RestClient.builder().defaultStatusHandler({ true }) { _, _ -> }.build() }

    @BeforeEach
    fun reset() {
        server.dispatcher = QueueDispatcher()
        gateway.circuitBreaker.reset()
        while (server.takeRequest(0, MILLISECONDS) != null) {}
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun traceIdOf(req: mockwebserver3.RecordedRequest): String {
        val h = assertNotNull(req.headers["traceparent"], "traceparent header missing")
        return assertNotNull(traceparent.matchEntire(h), "bad traceparent $h").groupValues[1]
    }

    @Test
    fun traceIdPropagatesToGroupServiceAndIntoProblem() {
        server.enqueue(json(503, "{}")); server.enqueue(json(503, "{}"))
        val r = client.get().uri("http://localhost:$port/api/members?size=2").retrieve().toEntity(String::class.java)
        assertEquals(503, r.statusCode.value())
        val traceId = mapper.readTree(r.body)["traceId"].asString()
        assertTrue(traceId.isNotBlank())
        assertEquals(traceId, traceIdOf(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!))
        assertEquals(traceId, traceIdOf(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!))
    }

    @Test
    fun failureLogCarriesTraceIdAndStage(output: CapturedOutput) {
        server.enqueue(json(503, "{}")); server.enqueue(json(503, "{}"))
        val r = client.get().uri("http://localhost:$port/api/members?size=2").retrieve().toEntity(String::class.java)
        val traceId = mapper.readTree(r.body)["traceId"].asString()
        val line = output.all.lines().firstOrNull { "stage=UPSTREAM_5XX" in it }
        assertNotNull(line, "no failure log line")
        assertTrue(traceId in line, "traceId $traceId not in: $line")
    }

    @Test
    fun successPathAlsoPropagates() {
        server.enqueue(json(200, """{"groups":[]}"""))
        val r = client.get().uri("http://localhost:$port/api/members?size=1").retrieve().toEntity(String::class.java)
        assertEquals(200, r.statusCode.value())
        assertTrue(traceparent.matches(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!.headers["traceparent"] ?: ""))
    }

    @Test
    fun prometheusExposesGatewayMetrics() {
        // Different page sizes = different memberIds batch sizes; the uri tag must not vary with them.
        listOf(1, 3).forEach {
            server.enqueue(json(200, """{"groups":[]}"""))
            client.get().uri("http://localhost:$port/api/members?size=$it").retrieve().toBodilessEntity()
        }
        server.enqueue(json(503, "{}")); server.enqueue(json(503, "{}"))
        client.get().uri("http://localhost:$port/api/members?size=1").retrieve().toBodilessEntity()
        val body = client.get().uri("http://127.0.0.1:$mgmtPort/actuator/prometheus").retrieve().body(String::class.java)!!
        listOf("group_call_failures_total", "group_call_duration_seconds_bucket", "resilience4j_circuitbreaker_state")
            .forEach { assertTrue(it in body, "$it missing") }
        val clientSeries = body.lines().filter { it.startsWith("http_client_requests_seconds_count") }
        assertTrue(clientSeries.any { """uri="/api/groups/summary"""" in it }, "client uri tag missing: $clientSeries")
        val leaked = body.lines().filter { Regex("""uri="[^"]*queryParam""").containsMatchIn(it) }
        assertTrue(leaked.isEmpty(), "query template vars in uri tag: ${leaked.take(2)}")
    }

    @Test
    @Order(Int.MAX_VALUE)
    fun readinessIgnoresGroupService() {
        server.close()
        val body = client.get().uri("http://127.0.0.1:$mgmtPort/actuator/health/readiness").retrieve().body(String::class.java)!!
        assertEquals("UP", mapper.readTree(body)["status"].asString())
    }
}
