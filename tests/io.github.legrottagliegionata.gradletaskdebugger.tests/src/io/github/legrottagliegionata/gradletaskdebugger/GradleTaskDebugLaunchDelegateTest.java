/*******************************************************************************
 * Copyright (c) 2026 Gionata Legrottaglie
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.legrottagliegionata.gradletaskdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.debug.core.DebugException;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaHotCodeReplaceListener;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.buildship.core.BuildConfiguration;
import org.eclipse.buildship.core.GradleCore;
import org.eclipse.buildship.core.GradleDistribution;
import org.eclipse.buildship.core.SynchronizationResult;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Imports a Gradle project, puts a breakpoint in its main class and launches its {@code run} task
 * in debug mode: the JVM forked by Gradle must stop at the breakpoint.
 */
class GradleTaskDebugLaunchDelegateTest {

  private static final String GRADLE_VERSION = System.getProperty("gradleVersion", "9.8.0");
  private static final int BREAKPOINT_LINE = 6;

  @TempDir static Path dir;
  private static ILaunchConfiguration configuration;

  private final BlockingQueue<IJavaThread> suspended = new LinkedBlockingQueue<>();
  private final IDebugEventSetListener breakpoints =
      events -> {
        for (DebugEvent event : events) {
          if (event.getKind() == DebugEvent.SUSPEND
              && event.getDetail() == DebugEvent.BREAKPOINT
              && event.getSource() instanceof IJavaThread thread) {
            suspended.add(thread);
          }
        }
      };

  @BeforeAll
  static void importSample() throws Exception {
    Path sample = copySample(dir.resolve("sample"));
    SynchronizationResult imported =
        GradleCore.getWorkspace()
            .createBuild(
                BuildConfiguration.forRootProjectDirectory(sample.toFile())
                    .overrideWorkspaceConfiguration(true)
                    .gradleDistribution(GradleDistribution.forVersion(GRADLE_VERSION))
                    .build())
            .synchronize(new NullProgressMonitor());
    assertTrue(imported.getStatus().isOK(), imported.getStatus().toString());

    IFile main =
        ResourcesPlugin.getWorkspace()
            .getRoot()
            .getProject("sample")
            .getFile("src/main/java/demo/Main.java");
    JDIDebugModel.createLineBreakpoint(
        main, "demo.Main", BREAKPOINT_LINE, -1, -1, 0, true, new HashMap<>());
    configuration =
        GradleRunConfigurations.getOrCreate(new TaskSelection(sample.toFile(), List.of(":run")));
    IWorkspaceDescription description = ResourcesPlugin.getWorkspace().getDescription();
    description.setAutoBuilding(true);
    ResourcesPlugin.getWorkspace().setDescription(description);
    ResourcesPlugin.getWorkspace()
        .build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
  }

  @BeforeEach
  void listenForBreakpoints() {
    DebugPlugin.getDefault().addDebugEventListener(breakpoints);
  }

  @AfterEach
  void stopListening() {
    DebugPlugin.getDefault().removeDebugEventListener(breakpoints);
  }

  @Test
  void stopsAtBreakpointInJvmForkedByGradle() throws Exception {
    CompletableFuture<ILaunch> launch =
        CompletableFuture.supplyAsync(() -> launchInDebugMode(configuration));

    IJavaThread thread = awaitBreakpoint();
    assertEquals(BREAKPOINT_LINE, thread.getTopStackFrame().getLineNumber());
    assertTrue(thread.getDebugTarget().canTerminate(), "Terminate must be able to stop the JVM");
    thread.resume();

    assertEnded(launch.get(5, TimeUnit.MINUTES));
  }

  @Test
  void terminatingTheBuildStopsItsJvms() throws Exception {
    CompletableFuture<ILaunch> launch =
        CompletableFuture.supplyAsync(() -> launchInDebugMode(configuration));

    IJavaThread thread = awaitBreakpoint();
    GradleBuildProcess build =
        Arrays.stream(thread.getLaunch().getProcesses())
            .filter(GradleBuildProcess.class::isInstance)
            .map(GradleBuildProcess.class::cast)
            .findFirst()
            .orElseThrow();
    build.terminate();

    assertEnded(launch.get(2, TimeUnit.MINUTES));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!thread.getDebugTarget().isTerminated() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertTrue(thread.getDebugTarget().isTerminated(), "The JVM must stop with the build");
  }

  @Test
  void replacesClassesSavedDuringTheDebugSession() throws Exception {
    BlockingQueue<IJavaDebugTarget> replaced = new LinkedBlockingQueue<>();
    List<String> failures = new CopyOnWriteArrayList<>();
    IJavaHotCodeReplaceListener hotCodeReplace =
        new IJavaHotCodeReplaceListener() {
          @Override
          public void hotCodeReplaceSucceeded(IJavaDebugTarget target) {
            replaced.add(target);
          }

          @Override
          public void hotCodeReplaceFailed(IJavaDebugTarget target, DebugException exception) {
            failures.add(String.valueOf(exception));
          }

          @Override
          public void obsoleteMethods(IJavaDebugTarget target) {}
        };
    IFile greeting =
        ResourcesPlugin.getWorkspace()
            .getRoot()
            .getProject("sample")
            .getFile("src/main/java/demo/Greeting.java");
    String original = new String(greeting.getContents().readAllBytes(), StandardCharsets.UTF_8);
    JDIDebugModel.addHotCodeReplaceListener(hotCodeReplace);
    try {
      CompletableFuture<ILaunch> launch =
          CompletableFuture.supplyAsync(() -> launchInDebugMode(configuration));
      IJavaThread thread = awaitBreakpoint();

      save(greeting, original.replace("Hello from", "Reloaded in"));
      IJavaDebugTarget target = replaced.poll(1, TimeUnit.MINUTES);
      assertEquals(
          thread.getDebugTarget(), target, "The saved class was not replaced: " + failures);
      assertEquals(List.of(), failures, "No replacement may fail, e.g. with classes ECJ compiled");
      thread.resume();

      assertEnded(launch.get(5, TimeUnit.MINUTES));
    } finally {
      JDIDebugModel.removeHotCodeReplaceListener(hotCodeReplace);
      save(greeting, original);
    }
  }

  /** Saves as the editor does: the class is compiled by the workspace auto-build. */
  private static void save(IFile file, String content) throws CoreException {
    file.setContents(
        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
        IResource.FORCE,
        new NullProgressMonitor());
  }

  private IJavaThread awaitBreakpoint() throws InterruptedException {
    IJavaThread thread = suspended.poll(5, TimeUnit.MINUTES);
    assertNotNull(thread, "The JVM forked by Gradle did not stop at the breakpoint");
    return thread;
  }

  private static void assertEnded(ILaunch launch) {
    assertTrue(
        Arrays.stream(launch.getProcesses()).allMatch(process -> process.isTerminated()),
        "The Gradle build and the debugger listener must end with the build");
    assertEquals(
        List.of(),
        new DebugInvocationCustomizer().getExtraArguments(),
        "No debug arguments may be left for other builds");
  }

  private static ILaunch launchInDebugMode(ILaunchConfiguration configuration) {
    try {
      return configuration.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
    } catch (CoreException e) {
      throw new CompletionException(e);
    }
  }

  private static Path copySample(Path target) throws IOException {
    Bundle bundle = FrameworkUtil.getBundle(GradleTaskDebugLaunchDelegateTest.class);
    for (URL entry : Collections.list(bundle.findEntries("resources/sample", "*", true))) {
      String path = entry.getPath().substring("/resources/sample/".length());
      if (!path.isEmpty() && !path.endsWith("/")) {
        Path file = target.resolve(path);
        Files.createDirectories(file.getParent());
        try (InputStream content = entry.openStream()) {
          Files.copy(content, file);
        }
      }
    }
    return target;
  }
}
