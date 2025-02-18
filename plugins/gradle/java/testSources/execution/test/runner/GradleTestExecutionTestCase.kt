// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.execution.test.runner

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.externalSystem.execution.ExternalSystemExecutionConsoleManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll.Companion.runAll
import com.intellij.testFramework.fixtures.BuildViewTestFixture
import com.intellij.util.LocalTimeCounter
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.plugins.gradle.execution.test.runner.fixture.TestExecutionConsoleEventCounter
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isSupportedJUnit5
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.testFramework.GradleProjectTestCase
import org.jetbrains.plugins.gradle.testFramework.util.tree.*
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.waitForTaskExecution
import org.junit.jupiter.api.AssertionFailureBuilder
import org.junit.jupiter.api.Assertions

abstract class GradleTestExecutionTestCase : GradleProjectTestCase() {

  private lateinit var testDisposable: Disposable
  private var _testExecutionEnvironment: ExecutionEnvironment? = null
  private var _testExecutionConsole: GradleTestsExecutionConsole? = null
  private lateinit var buildViewTestFixture: BuildViewTestFixture

  lateinit var testEventCounter: TestExecutionConsoleEventCounter

  val testExecutionEnvironment get() = _testExecutionEnvironment!!
  val testExecutionConsole get() = _testExecutionConsole!!

  override fun setUp() {
    super.setUp()

    testDisposable = Disposer.newDisposable()

    initExecutionConsoleHandler()

    testEventCounter = TestExecutionConsoleEventCounter()
    testEventCounter.install(project, testDisposable)

    buildViewTestFixture = BuildViewTestFixture(project)
    buildViewTestFixture.setUp()
  }

  override fun tearDown() {
    runAll(
      { buildViewTestFixture.tearDown() },
      { Disposer.dispose(testDisposable) },
      { super.tearDown() },
    )
  }

  val jUnitTestAnnotationClass: String
    get() = when (isSupportedJunit5()) {
      true -> "org.junit.jupiter.api.Test"
      else -> "org.junit.Test"
    }

  val jUnitIgnoreAnnotationClass: String
    get() = when (isSupportedJunit5()) {
      true -> "org.junit.jupiter.api.Disabled"
      else -> "org.junit.Ignore"
    }

  /**
   * Call this method inside [setUp] to print events trace to console
   */
  @Suppress("unused")
  fun initTextNotificationEventsPrinter() {
    val notificationManager = ExternalSystemProgressNotificationManager.getInstance()
    notificationManager.addNotificationListener(object : ExternalSystemTaskNotificationListenerAdapter() {
      override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
        if (id.type == ExternalSystemTaskType.EXECUTE_TASK) {
          when (stdOut) {
            true -> print(text)
            else -> System.err.print(text)
          }
        }
      }
    }, testDisposable)
  }

  private fun initExecutionConsoleHandler() {
    val consoleManager = object : GradleTestsExecutionConsoleManager() {
      override fun attachExecutionConsole(
        project: Project,
        task: ExternalSystemTask,
        env: ExecutionEnvironment?,
        processHandler: ProcessHandler?
      ) = super.attachExecutionConsole(project, task, env, processHandler)
        .also { _testExecutionEnvironment = env!! }
        .also { _testExecutionConsole = it!! }
    }
    ExtensionTestUtil.maskExtensions(ExternalSystemExecutionConsoleManager.EP_NAME, listOf(consoleManager), testDisposable)
  }

  private fun getTestExecutionTreeString(): String {
    return invokeAndWaitIfNeeded {
      val tree = testExecutionConsole.resultsViewer.treeView!!
        .also { PlatformTestUtil.waitWhileBusy(it) }
      TestConsoleProperties.HIDE_PASSED_TESTS.set(testExecutionConsole.properties, false)
        .also { PlatformTestUtil.waitWhileBusy(tree) }
      PlatformTestUtil.expandAll(tree)
        .also { PlatformTestUtil.waitWhileBusy(tree) }
      PlatformTestUtil.print(tree, false)
        .also { PlatformTestUtil.waitWhileBusy(tree) }
    }
  }

  private fun getTestExecutionConsoleString(): String {
    return invokeAndWaitIfNeeded {
      val tree = testExecutionConsole.resultsViewer.treeView!!
        .also { PlatformTestUtil.waitWhileBusy(it) }
      TestConsoleProperties.HIDE_PASSED_TESTS.set(testExecutionConsole.properties, false)
        .also { PlatformTestUtil.waitWhileBusy(tree) }
      PlatformTestUtil.expandAll(tree)
        .also { PlatformTestUtil.waitWhileBusy(tree) }
      TreeUtil.selectFirstNode(tree)
        .also { PlatformTestUtil.waitWhileBusy(tree) }
      val console = testExecutionConsole.console as ConsoleViewImpl
      console.flushDeferredText()
        .also { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
      console.text
    }
  }

  fun executeTasks(commandLine: String) {
    val runManager = RunManager.getInstance(project)
    val runConfigurationName = "GradleTestExecutionTestCase (" + LocalTimeCounter.currentTime() + ")"
    val runnerSettings = runManager.createConfiguration(runConfigurationName, GradleExternalTaskConfigurationType::class.java)
    val runConfiguration = runnerSettings.configuration as GradleRunConfiguration
    runConfiguration.rawCommandLine = commandLine
    runConfiguration.isForceTestExecution = true
    runConfiguration.settings.externalProjectPath = projectPath
    runConfiguration.settings.externalSystemIdString = GradleConstants.SYSTEM_ID.id
    val executorId = DefaultRunExecutor.EXECUTOR_ID
    val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)!!
    val runner = ProgramRunner.getRunner(executorId, runConfiguration)!!
    Assertions.assertEquals(ExternalSystemConstants.RUNNER_ID, runner.runnerId)
    val environment = ExecutionEnvironment(executor, runner, runnerSettings, project)
    waitForTaskExecution {
      runWriteActionAndWait {
        runner.execute(environment)
      }
    }
  }

  fun isSupportedTestLauncher(): Boolean {
    return isGradleAtLeast("7.6")
  }

  private fun isSupportedJunit5(): Boolean {
    return isSupportedJUnit5(gradleVersion)
  }

  fun assertBuildExecutionTree(assert: TreeAssertion<Nothing?>.() -> Unit) {
    buildViewTestFixture.assertBuildViewTreeEquals { treeString ->
      val tree = buildTree(treeString!!)
      TreeAssertion.assertTree(tree, assert)
    }
  }

  fun assertBuildExecutionTreeContains(assert: TreeAssertion<Nothing?>.() -> Unit) {
    buildViewTestFixture.assertBuildViewTreeEquals { treeString ->
      val tree = buildTree(treeString!!)
      TreeAssertion.assertMatchesTree(tree, assert)
    }
  }

  fun assertTestExecutionConsoleContains(expected: String) {
    val actual = getTestExecutionConsoleString()
    if (expected !in actual) {
      throw AssertionFailureBuilder.assertionFailure()
        .message("Test execution console doesn't contain")
        .expected(expected)
        .actual(actual)
        .build()
    }
  }

  fun assertTestExecutionTree(expected: String) {
    val treeString = getTestExecutionTreeString()
    val tree = buildTree(treeString)
    Assertions.assertEquals(expected, tree.sortedTree().getTreeString())
  }

  fun assertTestExecutionTree(assert: TreeAssertion<Nothing?>.() -> Unit) {
    val treeString = getTestExecutionTreeString()
    val tree = buildTree(treeString)
    TreeAssertion.assertTree(tree.sortedTree(), assert)
  }

  fun assertSMTestProxyTree(assert: TreeAssertion<AbstractTestProxy>.() -> Unit) {
    val treeView = testExecutionConsole.resultsViewer.treeView!!
    invokeAndWaitIfNeeded { PlatformTestUtil.waitWhileBusy(treeView) }
    val roots = testExecutionConsole.resultsViewer.root.children
    val tree = buildTree(roots, { name }, { children })
    runReadAction { // all navigation tests requires read action
      TreeAssertion.assertTree(tree.sortedTree(), assert)
    }
  }
}