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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.sourcelookup.AbstractSourceLookupDirector;
import org.eclipse.debug.core.sourcelookup.ISourceContainer;
import org.eclipse.debug.core.sourcelookup.ISourceLookupParticipant;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.sourcelookup.containers.JavaProjectSourceContainer;
import org.eclipse.jdt.launching.sourcelookup.containers.JavaSourceLookupParticipant;
import org.eclipse.jdt.launching.sourcelookup.containers.PackageFragmentRootSourceContainer;

/**
 * Source lookup for the JVMs of a Gradle build. A Gradle configuration does not name a Java
 * project, and the build may fork JVMs for any of its projects, so the sources are looked up in
 * every Java project of the workspace, then in the JDK and the libraries of their classpath.
 *
 * <p>The JDK and the libraries are the package fragment roots of the projects, with the sources
 * JDT attaches to them: for a Gradle project, the ones Buildship takes from the Gradle model when
 * it synchronizes the project. That is also where a Java application launch finds them: JDT
 * resolves the runtime classpath of its project, then maps every archive back to the root with
 * that path.
 */
final class SourceLookup extends AbstractSourceLookupDirector {

  private SourceLookup() {}

  static SourceLookup newDirector(ILaunchConfiguration configuration) {
    SourceLookup director = new SourceLookup();
    director.initializeParticipants();
    director.setSourceContainers(sourceContainers(workingDirectory(configuration)));
    return director;
  }

  @Override
  public void initializeParticipants() {
    addParticipants(new ISourceLookupParticipant[] {new JavaSourceLookupParticipant()});
  }

  /**
   * The projects first, whose sources are the ones being edited, then the libraries and JDKs of
   * each project in classpath order. A library of several projects is a root in each of them, with
   * different classpath attributes: it gets one container, keyed by path and name (the modules of
   * a JDK share the path of its jrt-fs.jar), from the first project that has it.
   *
   * <p>The projects in the directory of the build come first, so that a workspace with several
   * builds shows the versions of the libraries the launched build uses. Then the others, by name.
   */
  private static ISourceContainer[] sourceContainers(IPath workingDirectory) {
    IJavaProject[] projects;
    try {
      projects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
    } catch (JavaModelException e) {
      ILog.of(SourceLookup.class).log(e.getStatus());
      return new ISourceContainer[0];
    }
    Arrays.sort(
        projects,
        Comparator.comparing((IJavaProject project) -> !isIn(project, workingDirectory))
            .thenComparing(IJavaProject::getElementName));
    List<ISourceContainer> containers = new ArrayList<>();
    Map<List<Object>, ISourceContainer> libraries = new LinkedHashMap<>();
    for (IJavaProject project : projects) {
      containers.add(new JavaProjectSourceContainer(project));
      IPackageFragmentRoot[] roots;
      try {
        roots = project.getPackageFragmentRoots();
      } catch (JavaModelException e) {
        // E.g. a project closed meanwhile: the other projects keep their libraries.
        ILog.of(SourceLookup.class).log(e.getStatus());
        continue;
      }
      for (IPackageFragmentRoot root : roots) {
        try {
          // The source folders are in the project's container. A library missing on disk has no
          // classes to find.
          if (root.getResolvedClasspathEntry().getEntryKind() == IClasspathEntry.CPE_LIBRARY
              && root.exists()) {
            libraries.putIfAbsent(
                List.of(root.getPath(), root.getElementName()),
                new PackageFragmentRootSourceContainer(root));
          }
        } catch (JavaModelException e) {
          ILog.of(SourceLookup.class).log(e.getStatus());
        }
      }
    }
    containers.addAll(libraries.values());
    return containers.toArray(ISourceContainer[]::new);
  }

  private static boolean isIn(IJavaProject project, IPath directory) {
    IPath location = project.getProject().getLocation();
    return directory != null && location != null && directory.isPrefixOf(location);
  }

  /** The directory the build runs in, as Buildship resolves it, or null if it has none. */
  private static IPath workingDirectory(ILaunchConfiguration configuration) {
    try {
      String workingDir = configuration.getAttribute(GradleRunConfigurations.WORKING_DIR, "");
      return workingDir.isBlank()
          ? null
          : IPath.fromOSString(
              VariablesPlugin.getDefault()
                  .getStringVariableManager()
                  .performStringSubstitution(workingDir));
    } catch (CoreException e) {
      ILog.of(SourceLookup.class).log(e.getStatus());
      return null;
    }
  }
}
