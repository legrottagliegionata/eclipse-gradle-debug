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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** The init script that connects the JVMs forked by the build to the debugger. */
final class InitScript {

  /** System property with the port Eclipse listens on, read by the init script. */
  static final String PORT_PROPERTY = "gradleTaskDebugger.port";

  private static final String NAME = "gradle-task-debugger.init.gradle";

  private InitScript() {}

  /**
   * Gradle needs a file: the script is copied from the bundle to its state location, again at
   * every launch so that an updated plugin never runs an old script.
   */
  static synchronized Path file() throws CoreException {
    Bundle bundle = FrameworkUtil.getBundle(InitScript.class);
    Path file = Platform.getStateLocation(bundle).toPath().resolve(NAME);
    try (InputStream script = bundle.getEntry("scripts/" + NAME).openStream()) {
      Files.copy(script, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new CoreException(Status.error("Cannot write the Gradle init script " + file, e));
    }
    return file;
  }
}
