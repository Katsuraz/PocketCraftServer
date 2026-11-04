# PocketCraft Server

Experimental standalone Android UI for managing Minecraft Fabric server files.

## Current functionality
- Import Fabric server and mod JAR files into app-private storage.
- View log messages and request start/stop.
- Back up an existing world directory to an app-private ZIP.
- Build a debug APK via GitHub Actions.

## Not yet functional
**This version cannot run a Minecraft server.** Android does not ship a standard Java server JVM. The runtime, process management, foreground-service notification, Fabric/Geyser/Floodgate integration, remote tunnels, and world import/export are pending. Do not advertise this as a working crossplay host.

## Build
GitHub Actions → Build Android APK → Run workflow. Download the debug APK artifact.
