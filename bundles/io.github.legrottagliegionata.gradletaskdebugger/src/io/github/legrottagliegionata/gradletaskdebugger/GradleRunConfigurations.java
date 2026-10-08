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

import java.io.File;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

/**
 * Buildship's "Gradle Task" run configurations. The tasks of the Gradle Tasks view reuse the
 * configuration "Run Gradle Tasks" created for them, or create the same one, so that Run and
 * Debug share it.
 */
final class GradleRunConfigurations {

  static final String TYPE = "org.eclipse.buildship.core.launch.runconfiguration";

  // Attributes Buildship stores in its .launch files.
  private static final String TASKS = "tasks";
  private static final String WORKING_DIR = "working_dir";

  private GradleRunConfigurations() {}

  static ILaunchConfiguration getOrCreate(TaskSelection selection) throws CoreException {
    ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
    ILaunchConfigurationType type = manager.getLaunchConfigurationType(TYPE);
    String workingDir = workingDirExpression(selection.workingDir());
    for (ILaunchConfiguration configuration : manager.getLaunchConfigurations(type)) {
      if (selection.tasks().equals(configuration.getAttribute(TASKS, List.of()))
          && workingDir.equals(configuration.getAttribute(WORKING_DIR, ""))) {
        return configuration;
      }
    }
    String name = selection.workingDir().getName() + " - " + String.join(" ", selection.tasks());
    ILaunchConfigurationWorkingCopy configuration =
        type.newInstance(null, manager.generateLaunchConfigurationName(name.replace(':', '_')));
    configuration.setAttribute(TASKS, selection.tasks());
    configuration.setAttribute(WORKING_DIR, workingDir);
    return configuration.doSave();
  }

  /** As Buildship: the workspace location of the project in that directory, if there is one. */
  private static String workingDirExpression(File dir) {
    IPath location = IPath.fromFile(dir);
    for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
      if (location.equals(project.getLocation())) {
        return "${workspace_loc:" + project.getFullPath() + "}";
      }
    }
    return dir.getAbsolutePath();
  }
}
