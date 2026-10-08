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

import java.util.Arrays;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.sourcelookup.AbstractSourceLookupDirector;
import org.eclipse.debug.core.sourcelookup.ISourceContainer;
import org.eclipse.debug.core.sourcelookup.ISourceLookupParticipant;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.sourcelookup.containers.JavaProjectSourceContainer;
import org.eclipse.jdt.launching.sourcelookup.containers.JavaSourceLookupParticipant;

/**
 * Source lookup for the JVMs of a Gradle build. A Gradle configuration does not name a Java
 * project, so the sources are looked up in every Java project of the workspace, with the
 * libraries and source attachments of their classpath.
 */
final class SourceLookup extends AbstractSourceLookupDirector {

  private SourceLookup() {}

  static SourceLookup newDirector() {
    SourceLookup director = new SourceLookup();
    director.initializeParticipants();
    director.setSourceContainers(javaProjects());
    return director;
  }

  @Override
  public void initializeParticipants() {
    addParticipants(new ISourceLookupParticipant[] {new JavaSourceLookupParticipant()});
  }

  private static ISourceContainer[] javaProjects() {
    try {
      return Arrays.stream(JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects())
          .map(JavaProjectSourceContainer::new)
          .toArray(ISourceContainer[]::new);
    } catch (JavaModelException e) {
      ILog.of(SourceLookup.class).log(e.getStatus());
      return new ISourceContainer[0];
    }
  }
}
