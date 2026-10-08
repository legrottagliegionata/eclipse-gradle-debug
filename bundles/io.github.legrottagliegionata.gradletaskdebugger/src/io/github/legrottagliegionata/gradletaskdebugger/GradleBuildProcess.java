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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamsProxy;

/**
 * The Gradle build in the Debug view. Buildship does not add a process to its launches; this one
 * shows that the build is running and lets Terminate cancel it. The output stays in the Buildship
 * console, so there are no streams.
 */
final class GradleBuildProcess extends PlatformObject implements IProcess {

  private final ILaunch launch;
  private final String label;
  private final Map<String, String> attributes = new ConcurrentHashMap<>();
  private volatile boolean terminateRequested;
  private volatile boolean terminated;

  GradleBuildProcess(ILaunch launch, String label) {
    this.launch = launch;
    this.label = label;
    launch.addProcess(this);
    fire(DebugEvent.CREATE);
  }

  @Override
  public String getLabel() {
    return label;
  }

  @Override
  public ILaunch getLaunch() {
    return launch;
  }

  @Override
  public IStreamsProxy getStreamsProxy() {
    return null;
  }

  @Override
  public void setAttribute(String key, String value) {
    if (value == null) {
      attributes.remove(key);
    } else {
      attributes.put(key, value);
    }
  }

  @Override
  public String getAttribute(String key) {
    return attributes.get(key);
  }

  @Override
  public int getExitValue() throws DebugException {
    if (!terminated) {
      throw new DebugException(Status.error("The Gradle build is still running"));
    }
    return 0;
  }

  @Override
  public boolean canTerminate() {
    return !terminated && !terminateRequested;
  }

  @Override
  public boolean isTerminated() {
    return terminated;
  }

  /**
   * Asks Buildship to cancel the build, and stops the JVMs of the build: a JVM suspended by the
   * debugger may not handle the signal Gradle sends it. The process ends when the build does.
   */
  @Override
  public void terminate() throws DebugException {
    terminateRequested = true;
    fire(DebugEvent.CHANGE);
    for (IDebugTarget jvm : launch.getDebugTargets()) {
      if (jvm.canTerminate()) {
        jvm.terminate();
      }
    }
  }

  boolean isTerminateRequested() {
    return terminateRequested;
  }

  void terminated() {
    if (!terminated) {
      terminated = true;
      fire(DebugEvent.TERMINATE);
    }
  }

  private void fire(int kind) {
    DebugPlugin debug = DebugPlugin.getDefault();
    if (debug != null) {
      debug.fireDebugEventSet(new DebugEvent[] {new DebugEvent(this, kind)});
    }
  }
}
