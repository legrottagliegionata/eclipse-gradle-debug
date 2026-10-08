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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.eclipse.buildship.core.GradleBuild;
import org.eclipse.buildship.core.GradleCore;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunch;
import org.gradle.tooling.model.GradleProject;

/**
 * Hot code replace with the classes compiled by Gradle.
 *
 * <p>The JVMs of the build run the classes javac compiled, and those Eclipse compiles differ from
 * them (javac and ECJ name lambdas and other synthetic members differently): the JVM rejects them
 * as methods removed and added. When a Java file is saved, the classes of its project are compiled
 * by Gradle instead, and the class files Gradle rewrites are replaced in the JVMs.
 *
 * <p>Meanwhile the workspace auto-build waits for the end of the build, which Buildship runs in a
 * workspace operation, so JDT does not try to replace the classes Eclipse would compile. Nothing is
 * reloaded when "Build Automatically" is off.
 */
final class GradleReload implements IResourceChangeListener {

  private final ILaunch launch;
  private final Set<IProject> saved = ConcurrentHashMap.newKeySet();
  private final Map<IProject, GradleProject> gradleProjects = new ConcurrentHashMap<>();
  private final Job job;

  private GradleReload(ILaunch launch) {
    this.launch = launch;
    this.job = Job.create("Reload the classes compiled by Gradle", this::reloadSaved);
  }

  static GradleReload start(ILaunch launch) {
    GradleReload reload = new GradleReload(launch);
    ResourcesPlugin.getWorkspace()
        .addResourceChangeListener(reload, IResourceChangeEvent.POST_CHANGE);
    return reload;
  }

  void stop() {
    ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
    job.cancel();
  }

  @Override
  public void resourceChanged(IResourceChangeEvent event) {
    if (event.getDelta() == null || !ResourcesPlugin.getWorkspace().isAutoBuilding()) {
      return;
    }
    try {
      event
          .getDelta()
          .accept(
              delta -> {
                IResource resource = delta.getResource();
                if (resource.getType() == IResource.FILE
                    && "java".equals(resource.getFileExtension())
                    && !resource.isDerived()
                    && delta.getKind() != IResourceDelta.REMOVED) {
                  saved.add(resource.getProject());
                }
                return true;
              });
    } catch (CoreException e) {
      ILog.of(GradleReload.class).log(e.getStatus());
    }
    if (!saved.isEmpty()) {
      job.schedule(200);
    }
  }

  private void reloadSaved(IProgressMonitor monitor) throws CoreException {
    List<IProject> projects = new ArrayList<>(saved);
    saved.removeAll(projects);
    for (IProject project : projects) {
      try {
        reload(project, monitor);
      } catch (Exception e) {
        ILog.of(GradleReload.class)
            .log(Status.warning("Cannot reload the classes of " + project.getName(), e));
      }
    }
  }

  private void reload(IProject project, IProgressMonitor monitor) throws Exception {
    Optional<GradleBuild> build = GradleCore.getWorkspace().getBuild(project);
    if (build.isEmpty()) {
      return;
    }
    GradleProject gradleProject = gradleProject(project, build.get(), monitor);
    if (gradleProject == null) {
      return;
    }
    // A little earlier: file systems store modification times with a coarse resolution.
    FileTime compiling = FileTime.fromMillis(System.currentTimeMillis() - 1000);
    String path = ":".equals(gradleProject.getPath()) ? "" : gradleProject.getPath();
    build
        .get()
        .withConnection(
            connection -> {
              connection.newBuild().forTasks(path + ":classes").run();
              return null;
            },
            monitor);

    List<IResource> classFiles = new ArrayList<>();
    List<String> classNames = new ArrayList<>();
    Path projectDir = gradleProject.getProjectDirectory().toPath();
    Path classesDir = gradleProject.getBuildDirectory().toPath().resolve("classes");
    if (!Files.isDirectory(classesDir) || !classesDir.startsWith(projectDir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(classesDir)) {
      files
          .filter(file -> file.toString().endsWith(".class") && modifiedSince(file, compiling))
          .forEach(
              file -> {
                classFiles.add(project.getFile(IPath.fromPath(projectDir.relativize(file))));
                classNames.add(className(classesDir.relativize(file)));
              });
    }
    for (IResource classFile : classFiles) {
      classFile.refreshLocal(IResource.DEPTH_ZERO, monitor);
    }
    HotCodeReplace.replace(launch, classFiles, classNames);
  }

  /** The Gradle project in the directory of the Eclipse project, read once per debug session. */
  private GradleProject gradleProject(IProject project, GradleBuild build, IProgressMonitor monitor)
      throws Exception {
    GradleProject known = gradleProjects.get(project);
    if (known != null) {
      return known;
    }
    GradleProject root =
        build.withConnection(connection -> connection.getModel(GradleProject.class), monitor);
    GradleProject found = find(root, project.getLocation().toFile().getCanonicalFile());
    if (found != null) {
      gradleProjects.put(project, found);
    }
    return found;
  }

  private static GradleProject find(GradleProject project, File directory) throws IOException {
    if (directory.equals(project.getProjectDirectory().getCanonicalFile())) {
      return project;
    }
    for (GradleProject child : project.getChildren()) {
      GradleProject found = find(child, directory);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** {@code java/main/com/example/Foo$1.class}: the language and source set, then the class. */
  private static String className(Path relative) {
    String path = relative.subpath(2, relative.getNameCount()).toString();
    return path.substring(0, path.length() - ".class".length()).replace(File.separatorChar, '.');
  }

  private static boolean modifiedSince(Path file, FileTime time) {
    try {
      return Files.getLastModifiedTime(file).compareTo(time) >= 0;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
