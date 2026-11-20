package dev.task.member.infrastructure.group

import dev.task.group.api.GroupApi
import dev.task.group.api.MemberGroupDetailResponse
import dev.task.member.infrastructure.group.GroupCallStage.CIRCUIT_OPEN
import dev.task.member.infrastructure.group.GroupCallStage.CONCURRENCY_REJECTED
import dev.task.member.infrastructure.group.GroupCallStage.CONNECT_TIMEOUT
import dev.task.member.infrastructure.group.GroupCallStage.READ_TIMEOUT
import dev.task.member.infrastructure.group.GroupCallStage.RETRY_BUDGET_EXCEEDED
import dev.task.member.infrastructure.group.GroupCallStage.UPSTREAM_5XX
import dev.task.member.infrastructure.group.GroupCallStage.UPSTREAM_CONTRACT_ERROR
import dev.task.member.query.port.GroupReader
import dev.task.member.query.port.MemberGroupSummary
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryListener
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.core.retry.Retryable
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resilient [GroupReader] over the group-service HTTP client. Composition order is explicit (spec §8.2):
 * Semaphore (outermost, held across retries) -> RetryTemplate -> CircuitBreaker (per attempt) -> HTTP.
 */
@Component
class GroupServiceGateway(
    private val api: GroupApi,
    private val props: GroupClientProperties,
    private val meters: MeterRegistry,
) : GroupReader {
    private val log = LoggerFactory.getLogger(javaClass)
    private val permits = Semaphore(props.concurrencyLimit)
    private val inUse = meters.gauge("group.call.permits.in_use", AtomicInteger())

    private val retryPolicy = RetryPolicy.builder()
        .maxRetries(props.retry.maxRetries)
        .delay(props.retry.delay)
        .timeout(props.retry.timeout)
        .predicate(::isRetryable)
        .build()

    internal val circuitBreaker: CircuitBreaker = CircuitBreakerRegistry.of(
        props.circuitBreaker.let { b ->
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(b.slidingWindowSize)
                .minimumNumberOfCalls(b.minimumNumberOfCalls)
                .failureRateThreshold(b.failureRateThreshold)
                .waitDurationInOpenState(b.waitDurationInOpenState)
                .permittedNumberOfCallsInHalfOpenState(b.permittedCallsInHalfOpenState)
                // Not recorded = counted as success (NOT ignoreExceptions: ignored calls never close a half-open breaker).
                .recordException { classify(it) in setOf(CONNECT_TIMEOUT, READ_TIMEOUT, UPSTREAM_5XX) }
                .build()
        },
    ).also { TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(it).bindTo(meters) }.circuitBreaker("group")

    override fun findSummaries(memberIds: Collection<String>): Map<String, MemberGroupSummary> {
        val requested = memberIds.toSet()
        if (requested.isEmpty()) return emptyMap()
        return call("summaries") {
            api.getGroupSummaries(requested.toList()).groups
                .flatMap { g -> g.members.filter { it.memberId in requested }.map { it.memberId to MemberGroupSummary(g.groupName, it.permission) } }
                .toMap()
        }
    }

    override fun findDetail(memberId: String): MemberGroupDetailResponse? = call("detail") {
        try {
            // An empty 200 body comes back as null from the proxy despite the non-null contract type.
            (api.getMemberGroup(memberId) as MemberGroupDetailResponse?) ?: throw IllegalStateException("empty group detail body")
        } catch (e: HttpClientErrorException.NotFound) {
            // Inside the breaker on purpose: "no group" must be recorded as a success.
            if (e.getResponseBodyAs(ProblemDetail::class.java)?.properties?.get("code") == "MEMBER_GROUP_NOT_FOUND") null else throw e
        }
    }

    private fun <T> call(op: String, block: () -> T): T {
        val started = System.nanoTime()
        if (!permits.tryAcquire()) throw fail(op, CONCURRENCY_REJECTED, 0, started, null)
        inUse.incrementAndGet()
        val attempts = AtomicInteger()
        val timedOut = AtomicBoolean()
        val retry = RetryTemplate(retryPolicy).apply {
            setRetryListener(object : RetryListener {
                override fun onRetryPolicyTimeout(policy: RetryPolicy, retryable: Retryable<*>, e: RetryException) = timedOut.set(true)
            })
        }
        try {
            val result = retry.execute(Retryable { circuitBreaker.executeSupplier { attempts.incrementAndGet(); block() } })
            record(started, "success")
            return result
        } catch (e: RetryException) { // every failure, retried or not, arrives wrapped; cause = last attempt's exception
            // RetryTemplate checks the timeout before noticing maxRetries is used up, so the timeout callback also
            // fires after the last allowed attempt. Budget-exceeded only if a retry was actually skipped.
            val skippedRetry = timedOut.get() && attempts.get() <= props.retry.maxRetries
            throw fail(op, if (skippedRetry) RETRY_BUDGET_EXCEEDED else classify(e.cause), attempts.get(), started, e.cause)
        } finally {
            inUse.decrementAndGet()
            permits.release()
        }
    }

    private fun isRetryable(e: Throwable) = when (classify(e)) {
        CONNECT_TIMEOUT, READ_TIMEOUT -> true
        UPSTREAM_5XX -> generateSequence(e) { it.cause }.filterIsInstance<HttpServerErrorException>().any { it.statusCode.value() in 502..504 }
        else -> false
    }

    private fun classify(e: Throwable?): GroupCallStage {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { it is CallNotPermittedException } -> CIRCUIT_OPEN
            chain.any { it is HttpConnectTimeoutException || it is ConnectException } -> CONNECT_TIMEOUT
            chain.any { it is HttpTimeoutException || it is SocketTimeoutException } -> READ_TIMEOUT
            chain.any { it is HttpServerErrorException } -> UPSTREAM_5XX
            // Read-timeout during the body: Spring's JdkClientHttpRequest TimeoutHandler just closes the body
            // stream, so it surfaces as a plain IOException("closed"), not HttpTimeoutException. Resets/EOF land here too.
            chain.any { it is IOException } -> READ_TIMEOUT
            else -> UPSTREAM_CONTRACT_ERROR // 4xx, unreadable/empty body, unknown enum
        }
    }

    private fun fail(op: String, stage: GroupCallStage, attempts: Int, started: Long, cause: Throwable?): GroupServiceException {
        val elapsed = record(started, stage.name)
        meters.counter("group.call.failures", "stage", stage.name).increment()
        log.warn(
            "group call failed op={} stage={} attempts={} elapsedMs={} cause={}",
            op, stage, attempts, elapsed.toMillis(), cause?.javaClass?.name,
        )
        return GroupServiceException(stage, attempts, elapsed, cause)
    }

    private fun record(started: Long, outcome: String): Duration = Duration.ofNanos(System.nanoTime() - started).also {
        Timer.builder("group.call.duration").tag("outcome", outcome).publishPercentileHistogram().register(meters).record(it)
    }
}
