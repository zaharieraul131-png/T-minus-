# T-MINUS

A countdown/reminder Android app with local Android notifications and an optional Cloudflare Worker backend that sends daily email reminders through one connected Gmail account. Every countdown stores its own recipient email address.

> This is the first working starter version. Local countdowns and phone notifications work without a server. Automatic Gmail delivery becomes active only after the backend and Google OAuth setup are completed.

## What is included

- Android app built with Kotlin and Jetpack Compose.
- Countdown date selection using the Android date picker (day / month / year).
- One reminder time per countdown.
- Local daily Android notifications, including after a device restart.
- Per-countdown recipient email address.
- One connected Gmail sender for all countdown email.
- Cloudflare Worker scheduled every minute; it evaluates each countdown in its own IANA time zone.
- D1 database for countdowns, device authorization, Gmail connection metadata, and send history.
- Gmail refresh token encrypted with AES-GCM before it is stored in D1.
- GitHub Actions workflow that builds an installable debug APK after a push.

## Repository layout

```text
.github/workflows/build-apk.yml     # builds the APK on GitHub
android/                             # Android Studio / Gradle project
backend/                             # Cloudflare Worker + D1 migration
```

## 1. Upload this project to GitHub

Create a new repository named `T-MINUS`.

**Phone-friendly method using GitHub Codespaces:**

1. Upload `T-MINUS-GitHub-Starter.zip` to the empty repository and commit that ZIP file.
2. Open the repository's **Code → Codespaces** area and create a Codespace on the default branch.
3. In the Codespaces terminal, run these commands from the repository root:

```bash
unzip T-MINUS-GitHub-Starter.zip
cp -a tminus-project/. .
rm -rf tminus-project T-MINUS-GitHub-Starter.zip
git add .
git commit -m "Add T-MINUS Android app and email backend"
git push
```

The `cp -a` command also copies the hidden `.github` workflow folder. Keep the project folders in their original locations. The Android project is inside `android/`; the email backend is inside `backend/`.

After the source files are pushed to the default branch, open the repository's **Actions** tab. The workflow named **Build T-MINUS Android APK** should start automatically. When it succeeds:

1. Open the completed workflow run.
2. Scroll to **Artifacts**.
3. Download `T-MINUS-debug-apk`.
4. Extract the artifact ZIP and install `app-debug.apk` on your Android phone.

The debug APK is for your own testing; it is not a Play Store release. If the workflow fails, open the failed step and copy the first actual error plus about 20 lines around it.

## 2. Deploy the email backend

You need a Cloudflare account. These steps can be done from a computer or a Linux shell that has Node.js and npm. Cloudflare's Wrangler CLI is not officially supported on Android/Termux, so use GitHub Codespaces or a supported computer for this part.

From a terminal, enter the backend folder:

```bash
cd backend
npm install
npx wrangler login
```

Create the D1 database:

```bash
npx wrangler d1 create t-minus-db
```

Cloudflare prints a `database_id`. Copy that ID into `backend/wrangler.toml`, replacing `REPLACE_WITH_YOUR_D1_DATABASE_ID`.

Apply the database migration and deploy the Worker:

```bash
npm run db:migrate
npm run deploy
```

Copy the Worker URL printed by Wrangler. It will look similar to `https://t-minus-reminders.YOUR-SUBDOMAIN.workers.dev`.

## 3. Configure Gmail API and OAuth

Use a Google Cloud project that you control:

1. Enable the **Gmail API** for the project.
2. Configure the Google OAuth consent screen and add your own Google account as a test user while developing.
3. Create an OAuth client of type **Web application**.
4. Add this exact authorized redirect URI, replacing the example domain with your Worker URL:

```text
https://t-minus-reminders.YOUR-SUBDOMAIN.workers.dev/oauth/callback
```

5. Keep the OAuth client ID and client secret private. Do not put them in the Android app, GitHub repository, screenshots, or chat messages.

Back in `backend/`, add the four Worker secrets. Wrangler prompts you to enter each value privately:

```bash
npx wrangler secret put GOOGLE_CLIENT_ID
npx wrangler secret put GOOGLE_CLIENT_SECRET
npx wrangler secret put OAUTH_REDIRECT_URI
```

For `OAUTH_REDIRECT_URI`, enter the exact callback URL above.

Generate a 32-byte encryption key in a terminal:

```bash
openssl rand -base64 32
```

Then store it as a Cloudflare secret:

```bash
npx wrangler secret put TOKEN_ENCRYPTION_KEY
```

Paste the generated value into Wrangler's prompt. Do not commit it. The Worker uses this key to encrypt the Gmail refresh token before saving it in D1. Losing this key means the stored Gmail connection must be disconnected and authorized again.

You do not need to redeploy after setting Worker secrets, but you can run this to confirm the deployment is current:

```bash
npm run deploy
```

## 4. Connect the Android app

1. Open T-MINUS on your phone.
2. Tap the settings gear.
3. Paste your Cloudflare Worker URL into **Cloudflare Worker URL** and tap **SAVE BACKEND URL**.
4. Tap **CONNECT GMAIL**.
5. Choose the one Gmail account that should send all countdown emails and approve the requested send-only permission.
6. Return to T-MINUS and tap **CHECK GMAIL**.
7. Create a countdown, choose its date and reminder time, enable **Automatic email**, and enter that countdown's recipient address.

The Gmail permission is send-only (`gmail.send`). The backend does not request permission to read your inbox.

## 5. Important Gmail testing limitation

Google's OAuth documentation says refresh tokens for an External consent screen left in **Testing** expire after 7 days when the app requests scopes beyond basic profile/email. Since T-MINUS requests Gmail send permission, you may need to reconnect Gmail after a week during testing. For persistent use, review Google's OAuth publishing and verification requirements for the Gmail scope; do not try to bypass Google's warning or verification process.

Google may also revoke refresh tokens for other reasons. T-MINUS reports the backend connection status, but a revoked/expired Gmail grant must be authorized again.

## 6. Reminder behavior

- Reminders are sent once per local calendar day, at or after the chosen local time.
- The target date itself receives a final reminder; no reminders are sent after that date.
- The Android notification is scheduled on the phone. Android may delay inexact alarms during power-saving / Doze modes.
- Email reminders are sent by Cloudflare, so the phone does not need to be on or connected to the internet.
- Each countdown has its own destination email. All messages are sent by the one Gmail account connected in Settings.
- A failed email is attempted up to two times that day; a successful send is recorded to prevent a duplicate daily email.

## Troubleshooting information to send back

When something fails, send:

- The exact error text.
- Whether it happened in the GitHub Actions build, Cloudflare deployment, Google authorization, or the Android app.
- A screenshot of the error screen if possible.

Never send a Gmail password, Google verification code, OAuth client secret, encryption key, or any other secret.
