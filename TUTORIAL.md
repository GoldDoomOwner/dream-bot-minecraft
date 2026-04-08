# DreamBot — Step by Step Setup

## 1. Get the .jar (no JDK install)

1. Unzip `dreambot-source.zip` on your computer.
2. Sign up free at github.com (just need an email).
3. Top-right + → New repository. Name: `dreambot`. Public. Don't tick "Add README". Click Create.
4. Click the blue "uploading an existing file" link.
5. Show hidden files so the `.github` folder is visible:
   - Windows: open the unzipped folder, View tab → tick "Hidden items"
   - Mac: in Finder, press Cmd + Shift + .
6. Drag every file and folder from inside `dreambot` onto the GitHub upload page. You should see `.github`, `src`, `build.gradle`, `gradle.properties`, `settings.gradle`, `README.md` in the upload list. If `.github` is missing, the build won't run.
7. Scroll down, click Commit changes. Wait for the upload bar.
8. Click the Actions tab. "Build DreamBot" will be running with a yellow dot. Wait ~5 minutes for a green check.
9. Click into the run, scroll to Artifacts, click `dreambot-jar` to download a zip.
10. Inside that zip is `dreambot-1.0.0.jar`. That is your file.

## 2. Install Fabric

1. Go to fabricmc.net/use, download and run the Fabric Installer.
2. Pick Minecraft 1.21.11, Loader version 0.18.1 or newer, click Install.
3. Close the installer.

## 3. Get Fabric API

1. Go to modrinth.com/mod/fabric-api
2. Download the version for 1.21.11.

## 4. Install the mod

1. Open your `.minecraft` folder:
   - Windows: Win + R, type `%appdata%\.minecraft`, enter
   - Mac: Finder → Go menu → hold Option → Library → Application Support → minecraft
2. Open the `mods` folder. Make one if it doesn't exist.
3. Drop both jars in: `dreambot-1.0.0.jar` and the Fabric API jar.

## 5. Launch

1. Open the Minecraft launcher.
2. Pick the `fabric-loader-1.21.11` profile from the dropdown next to Play.
3. Hit Play.
4. In-game, press Right Shift to open the DreamBot menu.

## If something breaks

- Build fails on GitHub Actions (red X) → click the run, click the build job, scroll to the red step, copy the last 20 lines of error, share with whoever is helping you.
- Mod crashes on launch → open `.minecraft/logs/latest.log`, find the error, share that.
- Wrong Fabric version → 1.21.11 needs Fabric Loader 0.18.0 or newer. Reinstall Fabric.
- Mod loaded but commands missing → make sure Fabric API is also in your mods folder. DreamBot depends on it.
