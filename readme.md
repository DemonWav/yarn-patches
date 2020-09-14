Yarn Patches
============

Decompilation patches for Fabric's intermediary patches. This repo is named "Yarn"
because intermediary is an awful name and I'd rather avoid it.

This repo is based on [ForgedFlower](https://github.com/natanfudge/ForgedFlower).

A lot of the implementation for the build scripts in this repo adapted from
[Yarn](https://github.com/FabricMC/yarn), [Loom](https://github.com/FabricMC/fabric-loom),
and [ForgedFlowerLoom](https://github.com/natanfudge/ForgedFlowerLoom).

This repo is mostly a proof-of-concept / technical demo. As the Yarn ecosystem lacks a
significant amount of tooling necessary for high quality decompilation output that MCP uses,
and my general uninterest in reimplementing that tooling from scratch for no reason, this
is likely not to go anywhere.

Many of the decompilation issues which require patches to fix still are best fixed by adjusting
the bytecode before decompilation to allow the decompiler to do a better job. Still, with enough
patches this repo represents a system where users could easily contribute patches if they wanted.

Usage
-----

Create a patch workspace with the `setup` task. This will create 3 workspaces, `client`, `server`, and
`merged`. Use `setupClient`, `setupServer`, or `setupMerged` to only work on one environment instead:

```bash
# This command is the equivalent of the next 3 commands
./gradlew setup
# Individual workspaces
./gradlew setupClient
./gradlew setupServer
./gradlew setupMerged
```

This will set up a patch workspace in the `workspace/<minecraftVersion>` directory.

The version of Minecraft that will be set up is defined by the `minecraftVersion` property defined with
a default value in [`gradle.properties`](/gradle.properties).

To specify a different Minecraft version instead, provide the version on the command-line:

```bash
./gradlew setupServer -PminecraftVersion=1.16.2
```

It's fine to have as many patch workspaces in place as you like, you just need to call the setup task
for each version.

Generating Patches
------------------

Once you've made the changes you want to make, generate patches from those changes with:

```bash
# Template command
./gradlew :<minecraftVersion>:<environment>:generatePatches
# Example command
./gradlew :1.16.3:server:generatePatches
``` 

Clean Up
--------

There are 2 tasks defined for cleaning build output:
```
# Delete the workspace directory created by the setup task (workspace/)
./gradlew cleanWorkspace
# Delete the cache directory used to store task outputs before the final setup step (.gradle/cache/)
./gradlew cleanCache
```

Tips & Tricks
-------------

If using IntelliJ, I strongly recommend doing these 2 things to make building patches easier:

 1. Switch from using Gradle to build the project to IntelliJ. IntelliJ's compiler is much faster, especially in terms
    of incremental compilation. You're going to have to deal with a lot of compilation errors, so this will speed things
    up a lot.

    Set the following to `IntelliJ IDEA`:

    ```
    Settings -> Build, Execution, Deployment -> Build Tools -> Gradle -> Build and run using:
    ```
 2. Increase the error count from the Java compiler IntelliJ uses. You should expect a decompiled server with no patches
    to have something around 1000 to 1500 compilation errors, but javac has a default limit of 100 errors. To remove that
    limit and get a better sense of how many errors there are go to:

    ```
    Settings -> Build, Execution, Deployment -> Compiler -> Java Compiler -> Additional command line parameters
    ```

    and enter this value into the text box:

    ```
    -Xmaxerrs 100000 -Xmaxwarns 100000
    ```
