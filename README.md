# Gradle Task Debugger for Eclipse

Debug any Gradle task from Eclipse the way IntelliJ IDEA does it: right-click a task in the
**Gradle Tasks** view, choose **Debug Gradle Tasks**, and the debugger is attached to every JVM
the build forks (`JavaExec`, `run`, `bootRun`, `test`, ...).

No *Remote Java Application*, no *Launch Group*, no fixed debug port.

## Install

In Eclipse: *Help → Install New Software… → Add…* and use the update site

```
https://legrottagliegionata.github.io/eclipse-gradle-debug/
```

Requirements: Eclipse 2024-06 or later, Buildship 3.1 or later (included in the Eclipse IDE
packages), Java 17 or later.

## Use

- **Gradle Tasks view:** right-click one or more tasks → **Debug Gradle Tasks**.
- **Run configurations:** every *Gradle Task* configuration can now be launched in debug mode too,
  from the Debug toolbar button or from *Debug Configurations…*.

Breakpoints are hit in every JVM the build starts. The **Gradle build** entry in the Debug view
stops the build; terminating a JVM stops that JVM.

## How it works

1. Eclipse starts listening for debuggee JVMs on a free port.
2. Buildship runs the build exactly as in run mode, plus an
   [init script](bundles/io.github.legrottagliegionata.gradletaskdebugger/scripts/gradle-task-debugger.init.gradle)
   that configures every task implementing `JavaForkOptions` to start its JVM with the JDWP agent
   in client mode (`server=n,suspend=y`), pointing to that port.
3. Each forked JVM connects to Eclipse and waits for the debugger before running; the listener
   closes when the build ends.

Details:

- The init script sets `debugOptions` with `set()`, so it wins over conventions a build plugin may
  have configured. Gradle older than 5.6 gets the equivalent `-agentlib:jdwp` JVM argument.
- The port is read lazily from a system property, so the
  [configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html) is
  reused from one debug session to the next.
- The first debug launch of a configuration adds the standard JDT attribute
  `org.eclipse.jdt.launching.ALLOW_TERMINATE`, so that Terminate can stop the JVMs.
- Hot code replace uses the classes compiled by Gradle. The JVMs run what javac compiled, and
  Eclipse's compiler (ECJ) names lambdas and other synthetic members differently, so the JVM would
  reject its classes as methods removed and added. When a Java file is saved during a debug
  session, the plugin runs the `classes` task of its project and hands the class files Gradle
  rewrote to JDT's hot code replace, which redefines them and reinstalls the breakpoints. It takes
  as long as that Gradle compilation, and only runs when *Build Automatically* is on. Changes no
  JVM can redefine, such as new methods or fields, are reported by JDT as usual.
- Only JVMs forked by the build are debugged. To debug build logic (plugins, `buildSrc`), which
  runs in the Gradle daemon, use `-Dorg.gradle.debug=true`.
- Buildship does not export the model of the Gradle Tasks view: the plugin reads the selected
  nodes through their public getters, and is tested against Buildship 3.1.

## Build

```bash
./mvnw verify
```

The build uses [Tycho](https://github.com/eclipse-tycho/tycho) in pomless mode and needs Java 21.
The integration test imports a Gradle project, launches its `run` task in debug mode and checks
that a breakpoint is hit. Run it with Java 21 to 24: Buildship 3.1.10, in the 2024-06 target
platform, cannot import projects on Java 25. The update site is written to
`site/target/repository`.

## License

[Eclipse Public License 2.0](LICENSE)
