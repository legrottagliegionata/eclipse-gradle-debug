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

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMConnector;
import org.eclipse.jdt.launching.JavaRuntime;

/**
 * One debug launch of a Gradle build: Eclipse listens on a free port, the build gets the init
 * script that points its JVMs to that port, and the listener stops when the build ends.
 */
final class DebugSession implements AutoCloseable {

  private final ILaunch launch;
  private final GradleBuildProcess build;
  private final GradleReload reload;

  private DebugSession(ILaunch launch, GradleBuildProcess build) {
    this.launch = launch;
    this.build = build;
    this.reload = GradleReload.start(launch);
  }

  static DebugSession start(
      ILaunchConfiguration configuration, ILaunch launch, IProgressMonitor monitor)
      throws CoreException {
    allowTerminate(configuration);
    DebugSession session = new DebugSession(
        launch, new GradleBuildProcess(launch, "Gradle build: " + configuration.getName()));
    try {
      int port = freePort();
      IVMConnector connector = JavaRuntime.getVMConnector(
          IJavaLaunchConfigurationConstants.ID_SOCKET_LISTEN_VM_CONNECTOR);
      // connectionLimit 0: accept every JVM the build forks, e.g. several test workers.
      connector.connect(
          Map.of("port", Integer.toString(port), "connectionLimit", "0"), monitor, launch);
      DebugInvocationCustomizer.arm(
          launch,
          List.of(
              "--init-script",
              InitScript.file().toString(),
              "-D" + InitScript.PORT_PROPERTY + "=" + port));
      return session;
    } catch (CoreException | RuntimeException e) {
      session.close();
      throw e;
    }
  }

  /**
   * The monitor to pass to Buildship: Buildship cancels the build when its monitor is canceled,
   * and that is how terminating the Gradle build process stops the build.
   */
  IProgressMonitor monitor(IProgressMonitor monitor) {
    return new ProgressMonitorWrapper(monitor == null ? new NullProgressMonitor() : monitor) {
      @Override
      public boolean isCanceled() {
        return super.isCanceled() || build.isTerminateRequested();
      }
    };
  }

  /** Stops the JDT listeners, the other processes of the launch, and ends the build process. */
  @Override
  public void close() {
    reload.stop();
    DebugInvocationCustomizer.disarm(launch);
    for (IProcess process : launch.getProcesses()) {
      try {
        if (process != build && process.canTerminate()) {
          process.terminate();
        }
      } catch (DebugException e) {
        ILog.of(DebugSession.class).log(e.getStatus());
      }
    }
    build.terminated();
  }

  /**
   * The JDT listener reads from the configuration whether Terminate may stop the JVMs. Gradle
   * configurations do not have the attribute, so it is added once, as in a Java application.
   */
  private static void allowTerminate(ILaunchConfiguration configuration) {
    try {
      if (!configuration.hasAttribute(IJavaLaunchConfigurationConstants.ATTR_ALLOW_TERMINATE)) {
        ILaunchConfigurationWorkingCopy copy = configuration.getWorkingCopy();
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_ALLOW_TERMINATE, true);
        copy.doSave();
      }
    } catch (CoreException e) {
      // Not fatal: the JVMs can still be disconnected, and stopping the build stops them.
      ILog.of(DebugSession.class).log(e.getStatus());
    }
  }

  private static int freePort() throws CoreException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new CoreException(Status.error("No free port for the debugger", e));
    }
  }
}
