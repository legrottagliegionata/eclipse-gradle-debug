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

import java.util.Optional;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** "Debug Gradle Tasks" in the context menu of the Gradle Tasks view. */
public final class DebugTasksHandler extends AbstractHandler {

  @Override
  public Object execute(ExecutionEvent event) throws ExecutionException {
    Optional<TaskSelection> selection =
        HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection structured
            ? TaskSelection.of(structured)
            : Optional.empty();
    if (selection.isEmpty()) {
      MessageDialog.openInformation(
          HandlerUtil.getActiveShell(event),
          "Debug Gradle Tasks",
          "Select one or more tasks of the same Gradle build.");
      return null;
    }
    try {
      DebugUITools.launch(
          GradleRunConfigurations.getOrCreate(selection.get()), ILaunchManager.DEBUG_MODE);
    } catch (CoreException e) {
      throw new ExecutionException("Cannot create the Gradle run configuration", e);
    }
    return null;
  }
}
