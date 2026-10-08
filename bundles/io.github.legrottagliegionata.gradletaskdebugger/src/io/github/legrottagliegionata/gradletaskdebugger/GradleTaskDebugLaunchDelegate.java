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

import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchDelegate;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate2;

/**
 * Adds the "debug" mode to Buildship's "Gradle Task" run configurations.
 *
 * <p>Eclipse starts listening for JVMs, then Buildship runs the build as it does in "run" mode,
 * with an init script that makes every JVM forked by the build connect to that listener. The
 * launch ends with the build.
 */
public final class GradleTaskDebugLaunchDelegate implements ILaunchConfigurationDelegate2 {

  @Override
  public ILaunch getLaunch(ILaunchConfiguration configuration, String mode) {
    return new Launch(configuration, mode, SourceLookup.newDirector());
  }

  @Override
  public void launch(
      ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
      throws CoreException {
    try (DebugSession session = DebugSession.start(configuration, launch, monitor)) {
      // Blocks until the build ends, then Buildship removes the launch.
      runDelegate(configuration).launch(
          configuration, ILaunchManager.RUN_MODE, launch, session.monitor(monitor));
    }
  }

  @Override
  public boolean buildForLaunch(
      ILaunchConfiguration configuration, String mode, IProgressMonitor monitor)
      throws CoreException {
    return !(runDelegate(configuration) instanceof ILaunchConfigurationDelegate2 delegate)
        || delegate.buildForLaunch(configuration, ILaunchManager.RUN_MODE, monitor);
  }

  @Override
  public boolean finalLaunchCheck(
      ILaunchConfiguration configuration, String mode, IProgressMonitor monitor)
      throws CoreException {
    return !(runDelegate(configuration) instanceof ILaunchConfigurationDelegate2 delegate)
        || delegate.finalLaunchCheck(configuration, ILaunchManager.RUN_MODE, monitor);
  }

  @Override
  public boolean preLaunchCheck(
      ILaunchConfiguration configuration, String mode, IProgressMonitor monitor)
      throws CoreException {
    return !(runDelegate(configuration) instanceof ILaunchConfigurationDelegate2 delegate)
        || delegate.preLaunchCheck(configuration, ILaunchManager.RUN_MODE, monitor);
  }

  /** Buildship's own delegate, the one that runs the build in "run" mode. */
  private static ILaunchConfigurationDelegate runDelegate(ILaunchConfiguration configuration)
      throws CoreException {
    ILaunchDelegate[] delegates =
        configuration.getType().getDelegates(Set.of(ILaunchManager.RUN_MODE));
    if (delegates.length == 0) {
      throw new CoreException(
          Status.error("No Buildship launch delegate for " + configuration.getName()));
    }
    return delegates[0].getDelegate();
  }
}
