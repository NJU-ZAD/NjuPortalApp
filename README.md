# Automated NJU Authentication for Android Devices

## User

* Download apk https://github.com/NJU-ZAD/NjuPortalApp/releases
* Location service is required to allow automatic Wi-Fi detection.

## Developer

* Before running release workflow, ensure GitHub Actions has write permissions:
  1. Go to your repository > Settings > Actions > General.
  2. Set Workflow permissions to "Read and write permissions" and save.
  3. Check "Allow GitHub Actions to create and approve pull requests" if available.
* Before releasing a new version, update `versionCode` (increment) and `versionName` (e.g. "1.1") in `app/build.gradle` to ensure correct APK version.
* After editing, tag and push:
  * git tag v1.1
  * git push origin v1.1
