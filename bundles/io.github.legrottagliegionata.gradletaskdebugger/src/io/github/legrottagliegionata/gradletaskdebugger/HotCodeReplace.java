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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.JDIDebugModel;

/**
 * Hands class files to the hot code replace of JDT, which redefines them in the JVMs and then
 * reinstalls the breakpoints and drops the obsolete frames, as for the classes Eclipse compiles.
 *
 * <p>JDT only replaces the class files of the output folders Eclipse compiles to: the class files
 * Gradle compiles are passed to its internal entry point, whose signature has not changed since
 * Eclipse 2024-06.
 */
final class HotCodeReplace {

  private static final String MANAGER =
      "org.eclipse.jdt.internal.debug.core.hcr.JavaHotCodeReplaceManager";

  private HotCodeReplace() {}

  /**
   * @param classFiles the class files, in the workspace
   * @param classNames their binary names, in the same order
   */
  static void replace(ILaunch launch, List<IResource> classFiles, List<String> classNames)
      throws ReflectiveOperationException {
    List<IDebugTarget> jvms =
        Arrays.stream(launch.getDebugTargets())
            .filter(target -> target instanceof IJavaDebugTarget && !target.isTerminated())
            .toList();
    if (jvms.isEmpty() || classFiles.isEmpty()) {
      return;
    }
    Class<?> manager = Class.forName(MANAGER, true, JDIDebugModel.class.getClassLoader());
    Method doHotCodeReplace =
        manager.getDeclaredMethod("doHotCodeReplace", List.class, List.class, List.class);
    doHotCodeReplace.setAccessible(true);
    doHotCodeReplace.invoke(
        manager.getMethod("getDefault").invoke(null), jvms, classFiles, classNames);
  }
}
