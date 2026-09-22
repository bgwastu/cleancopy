# CleanCopy

CleanCopy makes clean copies of images, videos, and links before you share them.

## Screenshots

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/home.png" width="220" alt="CleanCopy home screen">
      <br>
      <sub>Home</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings.png" width="220" alt="CleanCopy settings screen">
      <br>
      <sub>Settings</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/history-detail.png" width="220" alt="CleanCopy history detail screen">
      <br>
      <sub>History detail</sub>
    </td>
  </tr>
</table>

## What it does

- Removes identifying metadata from images and videos.
- Cleans tracking parameters and redirect wrappers from links.
- Lets you review cleaned media before copying it or saving it to Downloads.
- Adds an optional Quick Settings tile for fast access.
- Keeps an optional local history of cleaned items.

## Install

Download the latest APK from [GitHub Releases](https://github.com/bgwastu/cleancopy/releases).

For automatic updates, install [Obtainium](https://github.com/ImranR98/Obtainium), tap **Add app**, and paste this repository URL:

```text
https://github.com/bgwastu/cleancopy
```

Obtainium watches the GitHub Releases page and can notify you when a new APK is available. CleanCopy does not currently have an app-store listing, so this is the simplest update path.

## Use it

1. Share an image or video to **Clean Media**, or add the CleanCopy Quick Settings tile.
2. From the tile, choose **Camera**, **Choose photo or video**, or **Current clipboard**.
3. CleanCopy removes supported metadata and shows the result without changing your clipboard.
4. Choose **Copy only** or **Save & Copy**.

Use **Clean current clipboard** when the media is already in your clipboard. Link cleaning is enabled from Settings.

## Build

```text
./gradlew lintDebug testDebugUnitTest assembleDebug
```

## License

[MIT](LICENSE) © Bagas Wastu
