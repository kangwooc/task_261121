package dev.task.member.infrastructure.group

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.group-client")
data class GroupClientProperties(
    val concurrencyLimit: Int = 50,
    val retry: Retry = Retry(),
    val circuitBreaker: Breaker = Breaker(),
) {
    data class Retry(
        val maxRetries: Long = 1,
        val delay: Duration = Duration.ofMillis(100),
        val timeout: Duration = Duration.ofMillis(1500),
    )

    data class Breaker(
        val slidingWindowSize: Int = 20,
        val minimumNumberOfCalls: Int = 10,
        val failureRateThreshold: Float = 50f,
        val waitDurationInOpenState: Duration = Duration.ofSeconds(10),
        val permittedCallsInHalfOpenState: Int = 3,
    )
}
