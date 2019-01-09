package io.kotlintest.runner.jvm.spec

import arrow.core.Failure
import arrow.core.Success
import io.kotlintest.Description
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestContext
import io.kotlintest.TestResult
import io.kotlintest.extensions.TopLevelTest
import io.kotlintest.runner.jvm.TestCaseExecutor
import io.kotlintest.runner.jvm.TestEngineListener
import io.kotlintest.runner.jvm.instantiateSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import io.kotlintest.TestType

/**
 * Implementation of [SpecRunner] that executes each [TestCase] in a fresh instance
 * of the [Spec] class.
 *
 * This differs from the [InstancePerLeafSpecRunner] in that
 * every single test, whether of type [TestType.Test] or [TestType.Container], will be
 * executed separately. Branch tests will ultimately be executed once as a standalone
 * test, and also as part of the "path" to any nested tests.
 *
 * So, given the following structure:
 *
 *  outerTest {
 *    innerTestA {
 *      // test
 *    }
 *    innerTestB {
 *      // test
 *    }
 *  }
 *
 * Three spec instances will be created. The execution process will be:
 *
 * spec1 = instantiate spec
 * spec1.outerTest
 * spec2 = instantiate spec
 * spec2.outerTest
 * spec2.innerTestA
 * spec3 = instantiate spec
 * spec3.outerTest
 * spec3.innerTestB
 */
class InstancePerTestCaseSpecRunner(listener: TestEngineListener,
                                    listenerExecutor: ExecutorService,
                                    scheduler: ScheduledExecutorService) : SpecRunner(listener) {

  private val logger = LoggerFactory.getLogger(this.javaClass)

  private val executed = HashSet<Description>()
  private val discovered = HashSet<Description>()
  private val queue = ArrayDeque<TestCase>()
  private val results = java.util.HashMap<TestCase, TestResult>()
  private val executor = TestCaseExecutor(listener, listenerExecutor, scheduler)

  /**
   * When executing a [TestCase], any child test cases that are found, are placed onto
   * a stack. When the test case has completed, we take the next test case from the
   * stack, and begin executing that.
   */
  override fun execute(spec: Spec, topLevelTests: List<TopLevelTest>): Map<TestCase, TestResult> {
    topLevelTests.filter { it.active }.forEach { enqueue(it.testCase) }
    while (queue.isNotEmpty()) {
      val element = queue.removeFirst()
      execute(element)
    }
    return results
  }

  private fun enqueue(testCase: TestCase) {
    if (discovered.contains(testCase.description))
      throw IllegalStateException("Cannot add duplicate test name ${testCase.name}")
    discovered.add(testCase.description)
    logger.debug("Enqueuing test ${testCase.description.fullName()}")
    queue.add(testCase)
  }

  /**
   * The intention of this runner is that each [TestCase] executes in it's own instance
   * of the containing [Spec] class. Therefore, when we begin executing a test case from
   * the queue, we must first instantiate a new spec, and begin execution on _that_ instance.
   *
   * As test lambdas are executed, nested test cases will be registered, these should be ignored
   * if they are not an ancestor of the target. If they are then we can step into them, and
   * continue recursively until we find the target.
   *
   * Once the target is found it can be executed as normal, and any test lambdas it contains
   * can be registered back with the stack for execution later.
   */
  private fun execute(testCase: TestCase) {
    logger.debug("Executing $testCase")
    instantiateSpec(testCase.spec::class).let {
      when (it) {
        is Failure -> throw it.exception
        is Success -> {
          val spec = it.value
          // each spec is allocated it's own thread so we can block here safely
          // allowing us to enter the coroutine world
          runBlocking {
            interceptSpec(spec) {
              spec.testCases().forEach { topLevel ->
                locate(testCase.description, topLevel, this)
              }
            }
          }
        }
      }
    }
  }

  private suspend fun locate(target: Description, current: TestCase, scope: CoroutineScope) {
    // if equals then we've found the test we want to invoke
    if (target == current.description) {
      val context = object : TestContext(scope.coroutineContext) {
        override fun description(): Description = target
        override suspend fun registerTestCase(testCase: TestCase) = enqueue(testCase)
      }
      if (executed.contains(target))
        throw  IllegalStateException("Attempting to execute duplicate test")
      executed.add(target)
      executor.execute(current, context) { results[current] = it }
      // otherwise if it's an ancestor then we want to search it recursively
    } else if (current.description.isAncestorOf(target)) {
      current.test.invoke(object : TestContext(scope.coroutineContext) {
        override fun description(): Description = current.description
        override suspend fun registerTestCase(testCase: TestCase) = locate(target, testCase, scope)
      })
    }
  }
}