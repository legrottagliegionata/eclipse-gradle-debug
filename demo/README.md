# Demo project

A small Gradle project to try the plugin, and to take its screenshots: an HTTP server started by
the `run` task, and a JUnit test.

1. Import it in Eclipse: *File → Import… → Gradle → Existing Gradle Project*, folder `demo`.
2. Put a breakpoint on the `greeting` line of `Greetings.greet`.
3. In the **Gradle Tasks** view, right-click `application → run` and choose **Debug Gradle Tasks**.
4. Open <http://localhost:8081/hello?name=Eclipse>: the debugger stops at the breakpoint.
5. Hot code replace: change the greeting, or the lambda in `DemoServer`, save, and call the URL
   again.
6. The same works for `verification → test`, which stops in `GreetingsTest`.

Stop the server with Terminate on the **Gradle build** entry of the Debug view.
