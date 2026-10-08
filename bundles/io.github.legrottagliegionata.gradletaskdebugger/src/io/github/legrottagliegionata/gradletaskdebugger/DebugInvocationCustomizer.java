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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.buildship.core.invocation.InvocationCustomizer;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunch;

/**
 * Adds the debug arguments to the Gradle build of a debug launch.
 *
 * <p>Buildship asks every customizer for extra arguments whenever it runs Gradle, without saying
 * for which launch. It builds the arguments of a launch inside the job that runs it, and that job
 * keeps its launch in a field: the arguments go only to the build whose job holds an armed launch.
 */
public final class DebugInvocationCustomizer implements InvocationCustomizer {

  private static final Map<ILaunch, List<String>> ARMED = new ConcurrentHashMap<>();

  static void arm(ILaunch launch, List<String> arguments) {
    ARMED.put(launch, arguments);
  }

  static void disarm(ILaunch launch) {
    ARMED.remove(launch);
  }

  @Override
  public List<String> getExtraArguments() {
    if (ARMED.isEmpty()) {
      return List.of();
    }
    Job job = Job.getJobManager().currentJob();
    ILaunch launch = job == null ? null : launchOf(job);
    return launch == null ? List.of() : ARMED.getOrDefault(launch, List.of());
  }

  private static ILaunch launchOf(Job job) {
    for (Class<?> type = job.getClass(); type != Job.class; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (ILaunch.class.isAssignableFrom(field.getType()) && field.trySetAccessible()) {
          try {
            return (ILaunch) field.get(job);
          } catch (IllegalAccessException e) {
            return null;
          }
        }
      }
    }
    return null;
  }
}
