// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.service.execution;

import com.intellij.build.events.EventResult;
import com.intellij.build.events.impl.FinishEventImpl;
import com.intellij.build.events.impl.ProgressBuildEventImpl;
import com.intellij.build.events.impl.StartEventImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent;
import com.intellij.openapi.externalSystem.model.task.event.OperationResult;
import com.intellij.openapi.externalSystem.model.task.event.TestOperationDescriptor;
import com.intellij.openapi.externalSystem.model.task.event.*;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.util.ObjectUtils;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import org.gradle.tooling.events.FailureResult;
import org.gradle.tooling.events.OperationDescriptor;
import org.gradle.tooling.events.SkippedResult;
import org.gradle.tooling.events.SuccessResult;
import org.gradle.tooling.events.*;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskProgressEvent;
import org.gradle.tooling.events.task.TaskStartEvent;
import org.gradle.tooling.events.task.TaskSuccessResult;
import org.gradle.tooling.events.test.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.util.GradleBundle;

import java.util.StringJoiner;

/**
 * @author Vladislav.Soroka
 */
public final class GradleProgressEventConverter {

  private static final Logger LOG = Logger.getInstance("com.intellij.openapi.externalSystem.event-processing");

  private static @NotNull String createEventId(@NotNull OperationDescriptor descriptor, @NotNull String operationId) {
    var joiner = new StringJoiner(" > ");
    joiner.add("[" + operationId + "]");
    var currentDescriptor = descriptor;
    while (currentDescriptor != null) {
      joiner.add("[" + currentDescriptor.getDisplayName() + "]");
      currentDescriptor = currentDescriptor.getParent();
    }
    return joiner.toString();
  }

  public static @Nullable ExternalSystemTaskNotificationEvent createTaskNotificationEvent(
    @NotNull ExternalSystemTaskId taskId,
    @NotNull String operationId,
    @NotNull ProgressEvent event
  ) {
    if (event instanceof TaskProgressEvent taskProgressEvent) {
      return convertTaskProgressEvent(taskProgressEvent, taskId, operationId);
    }
    return convertTestProgressEvent(event, taskId, operationId);
  }

  private static @NotNull ExternalSystemTaskNotificationEvent convertTaskProgressEvent(
    @NotNull TaskProgressEvent event,
    @NotNull ExternalSystemTaskId taskId,
    @NotNull String operationId
  ) {
    var eventId = createEventId(event.getDescriptor(), operationId);
    var eventTime = event.getEventTime();
    var message = event.getDescriptor().getName();

    if (event instanceof TaskStartEvent) {
      return new ExternalSystemBuildEvent(taskId, new StartEventImpl(eventId, taskId, eventTime, message));
    }
    else if (event instanceof TaskFinishEvent finishEvent) {
      var result = finishEvent.getResult();
      var eventResult = convertTaskProgressEventResult(result);
      if (eventResult != null) {
        return new ExternalSystemBuildEvent(taskId, new FinishEventImpl(eventId, taskId, eventTime, message, eventResult));
      }
    }
    else if (event instanceof StatusEvent statusEvent) {
      var total = statusEvent.getTotal();
      var progress = statusEvent.getProgress();
      var unit = statusEvent.getUnit();
      return new ExternalSystemBuildEvent(taskId, new ProgressBuildEventImpl(eventId, taskId, eventTime, message, total, progress, unit));
    }
    LOG.warn("Undefined Gradle event " + event.getClass().getSimpleName() + " " + event);
    var description = event.getDescriptor().getName();
    return new ExternalSystemTaskNotificationEvent(taskId, description);
  }

  private static @Nullable EventResult convertTaskProgressEventResult(@NotNull org.gradle.tooling.events.OperationResult result) {
    if (result instanceof SuccessResult) {
      var isUpToDate = result instanceof TaskSuccessResult && ((TaskSuccessResult)result).isUpToDate();
      return new com.intellij.build.events.impl.SuccessResultImpl(isUpToDate);
    }
    if (result instanceof FailureResult) {
      return new com.intellij.build.events.impl.FailureResultImpl(null, null);
    }
    if (result instanceof SkippedResult) {
      return new com.intellij.build.events.impl.SkippedResultImpl();
    }
    LOG.warn("Undefined operation result " + result.getClass().getSimpleName() + " " + result);
    return null;
  }

  private static @Nullable ExternalSystemTaskNotificationEvent convertTestProgressEvent(
    @NotNull ProgressEvent event,
    @NotNull ExternalSystemTaskId taskId,
    @NotNull String operationId
  ) {
    var eventId = createEventId(event.getDescriptor(), operationId);
    var parentEventId = ObjectUtils.doIfNotNull(event.getDescriptor().getParent(), it -> createEventId(it, operationId));
    var descriptor = convertTestDescriptor(event);

    if (event instanceof TestStartEvent) {
      var esEvent = new ExternalSystemStartEventImpl<>(eventId, parentEventId, descriptor);
      return new ExternalSystemTaskExecutionEvent(taskId, esEvent);
    }
    if (event instanceof TestFinishEvent finishEvent) {
      var result = finishEvent.getResult();
      var operationResult = convertTestProgressEventResult(result);
      if (operationResult != null) {
        var esEvent = new ExternalSystemFinishEventImpl<>(eventId, parentEventId, descriptor, operationResult);
        return new ExternalSystemTaskExecutionEvent(taskId, esEvent);
      }
    }
    if (event instanceof TestOutputEvent outputEvent) {
      var outputDescriptor = outputEvent.getDescriptor();
      var destination = outputDescriptor.getDestination();
      var isStdOut = destination == Destination.StdOut;
      var message = outputDescriptor.getMessage();
      var description = (isStdOut ? "StdOut" : "StdErr") + message;
      var esEvent = new ExternalSystemMessageEventImpl<>(eventId, parentEventId, descriptor, isStdOut, message, description);
      return new ExternalSystemTaskExecutionEvent(taskId, esEvent);
    }
    return null;
  }

  private static @Nullable OperationResult convertTestProgressEventResult(@NotNull TestOperationResult result) {
    var startTime = result.getStartTime();
    var endTime = result.getEndTime();
    if (result instanceof TestSuccessResult) {
      return new SuccessResultImpl(startTime, endTime, false);
    }
    if (result instanceof TestFailureResult failureResult) {
      var failures = ContainerUtil.map(failureResult.getFailures(), it -> convertTestFailure(it));
      return new FailureResultImpl(startTime, endTime, failures);
    }
    if (result instanceof TestSkippedResult) {
      return new SkippedResultImpl(startTime, endTime);
    }
    LOG.warn("Undefined test operation result " + result.getClass().getSimpleName() + " " + result);
    return null;
  }

  private static @NotNull Failure convertTestFailure(@NotNull org.gradle.tooling.Failure failure) {
    var message = failure.getMessage();
    var description = failure.getDescription();
    var causes = ContainerUtil.map(failure.getCauses(), it -> convertTestFailure(it));
    if (failure instanceof org.gradle.tooling.TestAssertionFailure assertionFailure) {
      var expectedText = assertionFailure.getExpected();
      var actualText = assertionFailure.getActual();
      if (message != null && expectedText != null && actualText != null) {
        return new TestAssertionFailure(message, description, causes, expectedText, actualText, null, null);
      }
    }
    return new FailureImpl(message, description, causes);
  }

  private static @NotNull TestOperationDescriptor convertTestDescriptor(@NotNull ProgressEvent event) {
    var descriptor = event.getDescriptor();
    var eventTime = event.getEventTime();
    var displayName = descriptor.getDisplayName();
    if (descriptor instanceof JvmTestOperationDescriptor jvmDescriptor) {
      var suiteName = jvmDescriptor.getSuiteName();
      var className = jvmDescriptor.getClassName();
      var methodName = jvmDescriptor.getMethodName();
      return new TestOperationDescriptorImpl(displayName, eventTime, suiteName, className, methodName);
    }
    return new TestOperationDescriptorImpl(displayName, eventTime, null, null, null);
  }

  public static @Nullable ExternalSystemTaskNotificationEvent convertProgressBuildEvent(
    @NotNull ExternalSystemTaskId taskId,
    @NotNull Object id,
    @NotNull ProgressEvent event
  ) {
    var total = -1L;
    var progress = -1L;
    var unit = "";
    if (event instanceof StatusEvent statusEvent) {
      total = statusEvent.getTotal();
      progress = statusEvent.getProgress();
      unit = statusEvent.getUnit();
    }
    var operationName = convertBuildEventDisplayName(event);
    if (operationName == null) {
      return null;
    }
    var esEvent = new ProgressBuildEventImpl(id, null, event.getEventTime(), operationName + "...", total, progress, unit);
    return new ExternalSystemBuildEvent(taskId, esEvent);
  }

  private static @Nullable @NlsSafe String convertBuildEventDisplayName(@NotNull ProgressEvent event) {
    @NlsSafe String operationName = event.getDescriptor().getName();
    if (operationName.startsWith("Download ")) {
      var path = operationName.substring("Download ".length());
      return GradleBundle.message("progress.title.download", PathUtil.getFileName(path));
    }
    if (event instanceof TaskProgressEvent) {
      return GradleBundle.message("progress.title.run.tasks");
    }
    if (event instanceof TestProgressEvent) {
      return GradleBundle.message("progress.title.run.tests");
    }
    if (event.getDisplayName().startsWith("Configure project ")) {
      return GradleBundle.message("progress.title.configure.projects");
    }
    else if (event.getDisplayName().startsWith("Cross-configure project ")) {
      return GradleBundle.message("progress.title.configure.projects");
    }
    return null;
  }

  public static @Nullable ExternalSystemTaskNotificationEvent legacyConvertProgressBuildEvent(
    @NotNull ExternalSystemTaskId taskId,
    @NotNull Object id,
    @NotNull String event
  ) {
    var total = -1L;
    var progress = -1L;
    var unit = "";
    var operationName = legacyConvertBuildEventDisplayName(event);
    if (operationName == null) {
      return null;
    }
    var esEvent = new ProgressBuildEventImpl(id, null, 0, operationName + "...", total, progress, unit);
    return new ExternalSystemBuildEvent(taskId, esEvent);
  }

  private static @Nullable @NlsSafe String legacyConvertBuildEventDisplayName(@NotNull String eventDescription) {
    if (eventDescription.startsWith("Download ")) {
      var path = eventDescription.substring("Download ".length());
      return GradleBundle.message("progress.title.download", PathUtil.getFileName(path));
    }
    if (eventDescription.startsWith("Task: ")) {
      return GradleBundle.message("progress.title.run.tasks");
    }
    if (eventDescription.equals("Build")) {
      return GradleBundle.message("progress.title.build");
    }
    if (eventDescription.startsWith("Build model ")) {
      return GradleBundle.message("progress.title.build.model");
    }
    else if (eventDescription.startsWith("Build parameterized model")) {
      return GradleBundle.message("progress.title.build.model");
    }
    if (eventDescription.startsWith("Configure project ")) {
      return GradleBundle.message("progress.title.configure.projects");
    }
    else if (eventDescription.startsWith("Cross-configure project ")) {
      return GradleBundle.message("progress.title.configure.projects");
    }
    return null;
  }

  public static @NotNull ExternalSystemTaskNotificationEvent legacyConvertTaskNotificationEvent(
    @NotNull ExternalSystemTaskId taskId,
    @NotNull String event
  ) {
    return new ExternalSystemTaskNotificationEvent(taskId, event);
  }
}
