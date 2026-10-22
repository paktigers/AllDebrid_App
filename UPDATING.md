# How to update the app (no more deleting the repository, no more uninstalling)

## Why you had to uninstall every time
Android only installs a new APK "over" an old one when both are signed with the SAME key and the new
version number is higher. GitHub debug builds use a different random key every time, and a recreated
repository restarts the version number. This project now fixes both:

- the APK is signed with YOUR permanent key (kept in GitHub Secrets),
- the version number grows with every build (100 + the GitHub run number),
- every signed build is published as a GitHub Release, and the app checks it for updates.

## One-time setup (about 5 minutes)
1. Keep ONE repository for the app. Make it **Public** (the app reads the public Releases list;
   your signing key is NOT in the repository, it is in Secrets, so it stays private).
2. Open `GITHUB_SECRETS_PRIVATE.txt` (sent to you separately, do not upload it to the repository).
3. On GitHub: repository > **Settings > Secrets and variables > Actions > New repository secret**
   - `KEYSTORE_PASSWORD` = the password from the file
   - `KEYSTORE_BASE64`   = the long text from the file (one line, no spaces)
4. Repository > **Settings > Actions > General > Workflow permissions** > choose
   **Read and write permissions** > Save (needed to publish the Release).
5. Upload the project files (see below). Wait for the build (3 to 6 minutes).
6. Repository > **Releases** > open the newest one > download **AllDebrid.apk**.
7. **Uninstall the old app one last time**, then install this APK. After this you never uninstall again.

## Every time there is a new version (no new repository!)
1. Open the SAME repository on GitHub.
2. **Add file > Upload files**, drag in the new files (the whole extracted project, or just the changed files).
   Files with the same path are replaced, new files are added. Press **Commit changes**.
   (On a PC you can also use GitHub Desktop: copy the files into the repository folder, Commit, Push.)
3. The build starts by itself and creates a new Release.
4. Open the app. It shows **Update available** (or Settings > App updates > Check for updates).
   Tap **Download & install**; the first time Android asks you to allow "Install unknown apps" for
   AllDebrid; then tap **Update**. Your keys, history and settings stay.

## Notes
- Changing only documentation (files in `docs/`, or any `.md` file such as README.md or UPDATING.md) does NOT start a build and does NOT
  create a new Release. To build anyway: Actions > Build APK > Run workflow.
- Back up `GITHUB_SECRETS_PRIVATE.txt`. If the key is lost you can create a new one, but then the next
  install needs one more uninstall.
- If you ever delete the repository and create a new one, the build number restarts at 100 + 1 and
  the app will not offer updates until the number is higher than the installed one. Keep the same repository.
- Without the two secrets the workflow still builds a debug APK, but it cannot update an installed app
  and no Release is published.
