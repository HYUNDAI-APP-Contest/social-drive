import com.socialdrive.app.domain.IntegrationContractsTest
import com.socialdrive.app.domain.RoutePrivacyTest
import com.socialdrive.app.domain.ConflictReviewTest
import com.socialdrive.app.domain.DeleteLifecycleTest
import com.socialdrive.app.data.remote.CloudDriveClientTest
import com.socialdrive.app.data.remote.RemoteDriveApiTest
import com.socialdrive.app.data.work.PublishRecoveryPolicyTest
import com.socialdrive.app.integration.NavigationGatewayCoordinatorTest
import com.socialdrive.app.integration.NavigationRequestControllerTest
import java.lang.reflect.InvocationTargetException
import org.junit.Test

fun main() {
    val expectedTests = 71
    val classes = listOf(
        IntegrationContractsTest::class.java,
        RoutePrivacyTest::class.java,
        ConflictReviewTest::class.java,
        DeleteLifecycleTest::class.java,
        CloudDriveClientTest::class.java,
        RemoteDriveApiTest::class.java,
        PublishRecoveryPolicyTest::class.java,
        NavigationRequestControllerTest::class.java,
        NavigationGatewayCoordinatorTest::class.java,
    )
    var executed = 0
    val failures = mutableListOf<String>()

    classes.forEach { testClass ->
        val instance = testClass.getDeclaredConstructor().newInstance()
        testClass.declaredMethods
            .filter { it.isAnnotationPresent(Test::class.java) }
            .sortedBy { it.name }
            .forEach { method ->
                executed += 1
                runCatching { method.invoke(instance) }
                    .onFailure { failure ->
                        val cause = (failure as? InvocationTargetException)?.targetException ?: failure
                        failures += "${testClass.simpleName}.${method.name}: ${cause.message ?: cause::class.java.simpleName}"
                    }
            }
    }

    failures.forEach(::println)
    check(failures.isEmpty()) { "${failures.size} core Kotlin test(s) failed" }
    check(executed == expectedTests) { "Expected $expectedTests core Kotlin tests, executed $executed" }
    println("Core Kotlin tests passed: $executed")
}
