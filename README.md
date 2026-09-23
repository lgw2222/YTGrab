# YTGrab for Android

Share any video (YouTube, X, Instagram, TikTok, Facebook, Reddit...) to **YTGrab** and it downloads to `Download/YTGrab` on your phone.

GitHub builds the app for you. You never need Android Studio.

## One-time setup
1. On github.com, click **+ > New repository**. Name it `ytgrab-android`, leave everything else alone, and click **Create repository**.
2. On the new repo page, click **uploading an existing file**.
3. Unzip `YTGrab-Android.zip` on your PC, open the `YTGrab-Android` folder, press **Ctrl+A**, and drag everything into the GitHub page.
   Make sure the `.github` folder is included.
4. Click **Commit changes**.
5. Open the **Actions** tab. A build called "Build YTGrab APK" runs for about 5 to 10 minutes. Wait for the green check.

## Install on your phone
On your phone, open:

    https://github.com/lgw2222/ytgrab-android/releases/latest/download/YTGrab.apk

Tap the downloaded file, allow installs from Chrome if Android asks, and tap **Install**.
(If Play Protect warns you, tap **More details > Install anyway**. The warning shows up because you built the app yourself.)

## Using it
- In YouTube, X, Instagram, or TikTok, tap **Share > YTGrab**. The download starts in the background with a progress notification.
- Or open YTGrab, paste a link, pick a format, and tap **Download**.
- Tap a finished download to open it. Files show up in your Gallery and in My Files > Download > YTGrab.
- The engine (yt-dlp) updates itself every 12 hours. You can also tap **Update engine** if a site stops working.

## Updating the app later
Upload the changed files to the repo again. GitHub builds a new version, and the same link installs it over the old one.

## If the build fails (red X in Actions)
Click the failed run, then **build**, and copy the red error text back to Claude.

## If the .github folder didn't upload
In the repo, click **Add file > Create new file**, name it `.github/workflows/build.yml`, paste in the contents of that file from the zip, and commit.
