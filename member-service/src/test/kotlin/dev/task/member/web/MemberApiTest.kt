package dev.task.member.web

import com.zaxxer.hikari.HikariDataSource
import dev.task.member.TestcontainersConfig
import dev.task.member.infrastructure.group.GroupServiceGateway
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val UNKNOWN = "zzzzzzzzzz"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig::class)
class MemberApiTest {
    companion object {
        private val server = MockWebServer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.http.serviceclient.group.base-url") { server.url("/").toString().removeSuffix("/") }
        }
    }

    @LocalServerPort private var port = 0
    @Autowired private lateinit var gateway: GroupServiceGateway
    @Autowired private lateinit var ds: HikariDataSource
    private val mapper = JsonMapper.builder().build()
    private var baseline = 0
    private val groupCalls get() = server.requestCount - baseline

    private val client by lazy {
        RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }) { _, _ -> }.build()
    }

    @BeforeEach
    fun reset() {
        server.dispatcher = QueueDispatcher()
        gateway.circuitBreaker.reset()
        while (server.takeRequest(0, MILLISECONDS) != null) {}
        baseline = server.requestCount
    }

    private fun json(code: Int, body: String, type: String = "application/json") =
        MockResponse.Builder().code(code).addHeader("Content-Type", type).body(body)

    private fun get(uri: String): Triple<Int, JsonNode, org.springframework.http.HttpHeaders> {
        val r = client.get().uri(uri).retrieve().toEntity(String::class.java)
        return Triple(r.statusCode.value(), mapper.readTree(r.body ?: "null"), r.headers)
    }

    /** First requested member gets group G1/Editor, the rest are ungrouped. */
    private fun groupFirstOnly() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ids = request.url.queryParameterValues("memberIds").filterNotNull().flatMap { it.split(",") }
                return json(200, """{"groups":[{"groupId":"g000000001","groupName":"G1","totalMemberCount":1,"members":[{"memberId":"${ids.first()}","permission":"Editor"}]}]}""").build()
            }
        }
    }

    @Test
    fun listJoinsGroupInfoAndCallsGroupOncePerPage() {
        groupFirstOnly()
        val (status, body) = get("/api/members?size=3")
        assertEquals(200, status)
        val members = body["members"]
        assertEquals(3, members.size())
        assertEquals("G1", members[0]["group"].asString())
        assertEquals("Editor", members[0]["permission"].asString())
        assertTrue(members[1]["group"].isNull)
        assertEquals(1, groupCalls)
        val requested = server.takeRequest().url.queryParameterValues("memberIds").filterNotNull().flatMap { it.split(",") }.toSet()
        assertEquals((0 until members.size()).map { members[it]["id"].asString() }.toSet(), requested)
        assertEquals(3, body["page"]["size"].asInt())
    }

    @Test
    fun ungroupedMemberHasNullGroup() {
        server.enqueue(json(200, """{"groups":[]}""").build())
        val (status, body) = get("/api/members?size=1&pagination=cursor")
        assertEquals(200, status)
        assertTrue(body["members"][0]["group"].isNull)
        assertTrue(body["members"][0]["permission"].isNull)
        assertTrue(body["cursor"]["hasNext"].asBoolean())
    }

    private val detailJson =
        """{"memberId":"27ysni0g1i","groupId":"g000000001","groupName":"Alpha","registeredDate":"2026-01-02 03:04","pricing":"Premium","permission":"Admin"}"""

    @Test
    fun detailCombinesGroupDetail() {
        server.enqueue(json(200, detailJson).build())
        val (status, body) = get("/api/member/27ysni0g1i")
        assertEquals(200, status)
        assertEquals("member1@example.com", body["email"].asString())
        assertEquals("2025-01-01 09:00", body["registeredDate"].asString())
        assertEquals("Premium", body["group"]["pricing"].asString())
        assertEquals("Admin", body["group"]["permission"].asString())
    }

    @Test
    fun detailWithMemberGroupNotFoundHasNullGroup() {
        server.enqueue(json(404, """{"status":404,"code":"MEMBER_GROUP_NOT_FOUND","traceId":null}""", "application/problem+json").build())
        val (status, body) = get("/api/member/27ysni0g1i")
        assertEquals(200, status)
        assertTrue(body["group"].isNull)
    }

    @Test
    fun unknownMemberIs404WithoutCallingGroup() {
        val (status, body) = get("/api/member/$UNKNOWN")
        assertEquals(404, status)
        assertEquals("MEMBER_NOT_FOUND", body["code"].asString())
        assertEquals(0, groupCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "page=1&cursor=abc",
        "pagination=cursor&page=1",
        "size=0", "size=101", "size=abc",
        "sort=email,asc",
        "page=-1", "page=2147483647", "page=30000000&size=100",
        "pagination=cursor&cursor=%21%21%21",
        "pagination=cursor&sort=name,desc&cursor=eyJuIjoieCIsInIiOjEsImkiOiJhYWFhYWFhYWFhIiwiZCI6IkFTQyJ9",
        "pagination=bogus",
    ])
    fun invalidRequestsAre400WithoutAnyGroupCall(query: String) {
        val (status, body) = get("/api/members?$query")
        assertEquals(400, status)
        assertEquals("INVALID_REQUEST", body["code"].asString())
        assertTrue(body.has("traceId"))
        assertEquals(0, groupCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ABC", "27ysni0g1", "27ysni0g1ii", "27ysni0g1!", "27YSNI0G1I"])
    fun invalidMemberIdIs400WithoutAnyCall(id: String) {
        val (status, body) = get("/api/member/$id")
        assertEquals(400, status)
        assertEquals("INVALID_REQUEST", body["code"].asString())
        assertEquals(0, groupCalls)
    }

    @Test
    fun emptyPageReturns200WithoutCallingGroupService() {
        val (status, body) = get("/api/members?page=999")
        assertEquals(200, status)
        assertEquals(0, body["members"].size())
        assertEquals(0, groupCalls)
    }

    @Test
    fun groupFailureIs503WithCodeAndTraceIdKey() {
        server.enqueue(json(503, "{}").build()); server.enqueue(json(503, "{}").build())
        val (status, body, headers) = get("/api/members?size=2")
        assertEquals(503, status)
        assertEquals("UPSTREAM_5XX", body["code"].asString())
        assertTrue(body.has("traceId"))
        assertTrue(headers["Retry-After"] == null)
    }

    @Test
    fun circuitOpenAddsRetryAfter() {
        gateway.circuitBreaker.transitionToOpenState()
        val (status, body, headers) = get("/api/members?size=2")
        assertEquals(503, status)
        assertEquals("CIRCUIT_OPEN", body["code"].asString())
        assertEquals("10", headers.getFirst("Retry-After"))
        assertEquals(0, groupCalls)
    }

    @Test
    fun dbConnectionIsReleasedDuringGroupCall() {
        server.enqueue(json(200, """{"groups":[]}""").headersDelay(800, MILLISECONDS).build())
        val call = CompletableFuture.supplyAsync { get("/api/members?size=2") }
        assertNotNull(server.takeRequest(2, SECONDS), "group call never arrived") // its response is still delayed
        assertEquals(0, ds.hikariPoolMXBean.activeConnections)
        assertEquals(200, call.get().first)
        assertEquals(1, groupCalls)
    }

    @Test
    fun unknownPathIs404ProblemJson() {
        val r = client.get().uri("/api/member/").retrieve().toEntity(String::class.java)
        assertEquals(404, r.statusCode.value())
        assertTrue(r.headers.contentType.toString().startsWith("application/problem+json"))
        val body = mapper.readTree(r.body)
        assertEquals("NOT_FOUND", body["code"].asString())
        assertTrue(body.has("traceId"))
        assertEquals(0, groupCalls)
    }

    @Test
    fun openApiDocIsServed() {
        val (status, body) = get("/v3/api-docs")
        assertEquals(200, status)
        assertTrue(body["paths"].has("/api/members"))
        assertTrue(body["paths"].has("/api/member/{memberId}"))
        listOf("/paths/~1api~1members/get/responses/400", "/paths/~1api~1members/get/responses/503",
            "/paths/~1api~1member~1{memberId}/get/responses/404").forEach { assertProblemJson(body.at(it), it) }
    }

    private fun assertProblemJson(response: JsonNode, at: String) {
        val content = response["content"]
        assertEquals(setOf("application/problem+json"), content.propertyNames().toSet(), at)
        assertTrue(content.at("/application~1problem+json/schema/\$ref").asString().endsWith("/ProblemDetail"), at)
    }

    @Test
    fun postIs405ProblemJson() {
        val r = client.post().uri("/api/members").retrieve().toEntity(String::class.java)
        assertEquals(405, r.statusCode.value())
        val body = mapper.readTree(r.body)
        assertEquals("METHOD_NOT_ALLOWED", body["code"].asString())
        assertTrue(body.has("traceId"))
        assertEquals(0, groupCalls)
    }
}
