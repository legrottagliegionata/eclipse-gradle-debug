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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.jface.viewers.IStructuredSelection;

/**
 * The tasks selected in Buildship's Gradle Tasks view, with the directory to run them from: the
 * same values Buildship puts in the configuration of "Run Gradle Tasks".
 *
 * <p>Buildship does not export the classes of that view, so its nodes are read through their
 * public getters.
 */
record TaskSelection(File workingDir, List<String> tasks) {

  /** Empty unless every selected node is a task and they all run from the same directory. */
  static Optional<TaskSelection> of(IStructuredSelection selection) {
    File workingDir = null;
    List<String> tasks = new ArrayList<>();
    for (Object node : selection) {
      Optional<TaskSelection> task = ofNode(node);
      if (task.isEmpty() || (workingDir != null && !workingDir.equals(task.get().workingDir()))) {
        return Optional.empty();
      }
      workingDir = task.get().workingDir();
      tasks.addAll(task.get().tasks());
    }
    return workingDir == null
        ? Optional.empty()
        : Optional.of(new TaskSelection(workingDir, List.copyOf(tasks)));
  }

  private static Optional<TaskSelection> ofNode(Object node) {
    try {
      Object project = get(node, "getParentProjectNode");
      return switch (node.getClass().getSimpleName()) {
        // A task of a project: its path (:sub:task), run from the root of its build.
        case "ProjectTaskNode" -> Optional.of(new TaskSelection(
            (File) get(get(get(project, "getBuildNode"), "getBuildTreeNode"), "getRootProjectDir"),
            List.of((String) get(node, "getPath"))));
        // A task selector: its name, run from its project so that it runs in the subprojects too.
        case "TaskSelectorNode" -> Optional.of(new TaskSelection(
            (File) get(get(project, "getEclipseProject"), "getProjectDirectory"),
            List.of((String) get(node, "getName"))));
        default -> Optional.empty();
      };
    } catch (ReflectiveOperationException | ClassCastException e) {
      return Optional.empty();
    }
  }

  private static Object get(Object target, String getter) throws ReflectiveOperationException {
    Method method = target.getClass().getMethod(getter);
    method.trySetAccessible();
    return method.invoke(target);
  }
}
