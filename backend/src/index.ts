interface D1Result<T = Record<string, unknown>> {
  success?: boolean;
  results?: T[];
  meta?: { changes?: number; duration?: number };
}
interface D1Statement {
  bind(...values: unknown[]): D1Statement;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<D1Result<T>>;
  run(): Promise<D1Result>;
}
interface D1Database {
  prepare(query: string): D1Statement;
}
interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
}
interface ScheduledController {
  scheduledTime: number;
  cron: string;
}
interface Env {
  DB: D1Database;
  GOOGLE_CLIENT_ID: string;
  GOOGLE_CLIENT_SECRET: string;
  OAUTH_REDIRECT_URI: string;
  TOKEN_ENCRYPTION_KEY: string;
}
interface ReminderRow {
  id: string;
  device_id: string;
  title: string;
  target_date: string;
  recipient_email: string;
  reminder_time: string;
  timezone: string;
  email_enabled: number;
}
interface GmailRow {
  email: string;
  refresh_token_ciphertext: string;
  refresh_token_iv: string;
}

const GMAIL_SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
const EMAIL_PATTERN = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, DELETE, OPTIONS",
  "access-control-allow-headers": "Authorization, Content-Type"
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function html(message: string, status = 200): Response {
  // The only messages passed here are fixed server-authored strings.
  return new Response(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>T-MINUS</title><style>body{margin:0;background:#101820;color:#eaf0f3;font:16px system-ui;display:grid;min-height:100vh;place-items:center}.card{max-width:420px;margin:24px;padding:28px;background:#1a2731;border:1px solid #42515d;border-radius:16px}h1{color:#f39a42;letter-spacing:3px}p{line-height:1.6;color:#bdc9d0}</style></head><body><main class="card"><h1>T-MINUS</h1><p>${message}</p><p>You can return to the T-MINUS Android app.</p></main></body></html>`, {
    status,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" }
  });
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function decodeBase64(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function bearerToken(request: Request): string | null {
  const header = request.headers.get("authorization") || "";
  const match = header.match(/^Bearer\s+([A-Za-z0-9_-]{40,80})$/i);
  return match?.[1] ?? null;
}

async function authenticate(request: Request, env: Env): Promise<string | null> {
  const token = bearerToken(request);
  if (!token) return null;
  const hash = await sha256Hex(token);
  const row = await env.DB.prepare("SELECT device_id FROM devices WHERE api_key_hash = ?")
    .bind(hash).first<{ device_id: string }>();
  return row?.device_id ?? null;
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const text = await request.text();
  if (text.length > 12_000) throw new Error("Request body is too large.");
  const value = JSON.parse(text || "{}");
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Expected a JSON object.");
  return value as Record<string, unknown>;
}

function isIsoDate(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function isValidTime(value: unknown): value is string {
  return typeof value === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
}

function validTimezone(value: unknown): value is string {
  if (typeof value !== "string" || value.length > 80) return false;
  try {
    new Intl.DateTimeFormat("en-GB", { timeZone: value }).format(new Date());
    return true;
  } catch {
    return false;
  }
}

function requireOAuthConfig(env: Env): void {
  if (!env.GOOGLE_CLIENT_ID || !env.GOOGLE_CLIENT_SECRET || !env.OAUTH_REDIRECT_URI || !env.TOKEN_ENCRYPTION_KEY) {
    throw new Error("The backend is missing one or more Google OAuth or encryption secrets.");
  }
  if (!env.OAUTH_REDIRECT_URI.startsWith("https://")) throw new Error("OAuth redirect URI must use HTTPS.");
}

async function encryptionKey(env: Env): Promise<CryptoKey> {
  const bytes = decodeBase64(env.TOKEN_ENCRYPTION_KEY);
  if (bytes.length !== 32) throw new Error("TOKEN_ENCRYPTION_KEY must decode to exactly 32 bytes.");
  return crypto.subtle.importKey("raw", bytes, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

async function encryptRefreshToken(token: string, env: Env): Promise<{ ciphertext: string; iv: string }> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await encryptionKey(env);
  const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(token));
  return { ciphertext: base64Url(new Uint8Array(encrypted)), iv: base64Url(iv) };
}

async function decryptRefreshToken(row: GmailRow, env: Env): Promise<string> {
  const key = await encryptionKey(env);
  const decrypted = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: decodeBase64(row.refresh_token_iv) },
    key,
    decodeBase64(row.refresh_token_ciphertext)
  );
  return new TextDecoder().decode(decrypted);
}

async function registerDevice(request: Request, env: Env): Promise<Response> {
  const token = bearerToken(request);
  if (!token) return json({ error: "A device token is required." }, 401);
  const hash = await sha256Hex(token);
  await env.DB.prepare(
    "INSERT OR IGNORE INTO devices (device_id, api_key_hash, created_at) VALUES (?, ?, ?)"
  ).bind(hash, hash, new Date().toISOString()).run();
  return json({ registered: true });
}

async function startGmailConnect(deviceId: string, env: Env): Promise<Response> {
  requireOAuthConfig(env);
  const state = base64Url(crypto.getRandomValues(new Uint8Array(32)));
  const stateHash = await sha256Hex(state);
  const expiresAt = Math.floor(Date.now() / 1000) + 10 * 60;
  await env.DB.prepare("INSERT INTO oauth_states (state_hash, device_id, expires_at) VALUES (?, ?, ?)")
    .bind(stateHash, deviceId, expiresAt).run();

  const authorization = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  authorization.search = new URLSearchParams({
    client_id: env.GOOGLE_CLIENT_ID,
    redirect_uri: env.OAUTH_REDIRECT_URI,
    response_type: "code",
    scope: `openid email ${GMAIL_SEND_SCOPE}`,
    access_type: "offline",
    prompt: "consent",
    include_granted_scopes: "true",
    state
  }).toString();
  return json({ authUrl: authorization.toString() });
}

async function oauthCallback(request: Request, env: Env): Promise<Response> {
  try {
    requireOAuthConfig(env);
    const url = new URL(request.url);
    const state = url.searchParams.get("state") || "";
    const code = url.searchParams.get("code") || "";
    const oauthError = url.searchParams.get("error");
    if (!state) return html("Authorization could not be verified. Return to the app and try again.", 400);

    const stateHash = await sha256Hex(state);
    const stateRow = await env.DB.prepare("SELECT device_id, expires_at FROM oauth_states WHERE state_hash = ?")
      .bind(stateHash).first<{ device_id: string; expires_at: number }>();
    await env.DB.prepare("DELETE FROM oauth_states WHERE state_hash = ?").bind(stateHash).run();
    if (!stateRow || stateRow.expires_at < Math.floor(Date.now() / 1000)) {
      return html("This Google authorization link expired. Return to T-MINUS and connect Gmail again.", 400);
    }
    if (oauthError || !code) return html("Gmail authorization was not completed. You can return to T-MINUS and try again.", 400);

    const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        code,
        client_id: env.GOOGLE_CLIENT_ID,
        client_secret: env.GOOGLE_CLIENT_SECRET,
        redirect_uri: env.OAUTH_REDIRECT_URI,
        grant_type: "authorization_code"
      })
    });
    const tokenData = await tokenResponse.json() as {
      access_token?: string; refresh_token?: string; error_description?: string; error?: string;
    };
    if (!tokenResponse.ok || !tokenData.access_token) {
      return html(`Google could not complete the connection: ${escapeHtml(tokenData.error_description || tokenData.error || "token exchange failed")}.`, 400);
    }

    const profileResponse = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
      headers: { authorization: `Bearer ${tokenData.access_token}` }
    });
    const profile = await profileResponse.json() as { email?: string };
    if (!profileResponse.ok || !profile.email) return html("Google authorized the app, but the email address could not be confirmed.", 400);

    const existing = await env.DB.prepare("SELECT email, refresh_token_ciphertext, refresh_token_iv FROM gmail_accounts WHERE device_id = ?")
      .bind(stateRow.device_id).first<GmailRow>();
    const reusableExistingToken = existing?.email === profile.email ? await decryptRefreshToken(existing, env) : null;
    const refreshToken = tokenData.refresh_token || reusableExistingToken;
    if (!refreshToken) return html("Google did not return a reusable offline token for this sender account. Revoke the old T-MINUS grant in Google Account permissions and connect again.", 400);

    const encrypted = await encryptRefreshToken(refreshToken, env);
    await env.DB.prepare(`
      INSERT INTO gmail_accounts (device_id, email, refresh_token_ciphertext, refresh_token_iv, updated_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(device_id) DO UPDATE SET
        email = excluded.email,
        refresh_token_ciphertext = excluded.refresh_token_ciphertext,
        refresh_token_iv = excluded.refresh_token_iv,
        updated_at = excluded.updated_at
    `).bind(stateRow.device_id, profile.email, encrypted.ciphertext, encrypted.iv, new Date().toISOString()).run();

    return html(`Gmail is connected as <strong>${escapeHtml(profile.email)}</strong>.`);
  } catch (error) {
    return html(`The connection could not be completed: ${escapeHtml(error instanceof Error ? error.message : "unknown error")}.`, 500);
  }
}

function escapeHtml(value: string): string {
  const replacements: Record<string, string> = {
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
  };
  return value.replace(/[&<>"']/g, (char) => replacements[char] || char);
}

async function gmailStatus(deviceId: string, env: Env): Promise<Response> {
  const account = await env.DB.prepare("SELECT email FROM gmail_accounts WHERE device_id = ?")
    .bind(deviceId).first<{ email: string }>();
  return json({ connected: Boolean(account), email: account?.email || "" });
}

async function disconnectGmail(deviceId: string, env: Env): Promise<Response> {
  const row = await env.DB.prepare("SELECT email, refresh_token_ciphertext, refresh_token_iv FROM gmail_accounts WHERE device_id = ?")
    .bind(deviceId).first<GmailRow>();
  if (row) {
    try {
      const token = await decryptRefreshToken(row, env);
      await fetch("https://oauth2.googleapis.com/revoke", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ token })
      });
    } catch {
      // Local credentials are deleted even if Google's revoke endpoint is unavailable.
    }
  }
  await env.DB.prepare("DELETE FROM gmail_accounts WHERE device_id = ?").bind(deviceId).run();
  return json({ disconnected: true });
}

async function upsertReminder(deviceId: string, body: Record<string, unknown>, env: Env): Promise<Response> {
  const id = typeof body.id === "string" ? body.id : "";
  const title = typeof body.title === "string" ? body.title.trim() : "";
  const targetDate = body.targetDate;
  const recipientEmail = typeof body.recipientEmail === "string" ? body.recipientEmail.trim() : "";
  const reminderTime = body.reminderTime;
  const timezone = body.timezone || "Europe/Bucharest";
  const notificationEnabled = body.notificationEnabled === true;
  const emailEnabled = body.emailEnabled === true;

  if (!/^[A-Za-z0-9_-]{6,64}$/.test(id)) return json({ error: "Invalid countdown ID." }, 400);
  if (!title || title.length > 80) return json({ error: "Countdown name must contain 1 to 80 characters." }, 400);
  if (!isIsoDate(targetDate)) return json({ error: "Target date must use YYYY-MM-DD." }, 400);
  if (!isValidTime(reminderTime)) return json({ error: "Reminder time must use HH:mm." }, 400);
  if (!validTimezone(timezone)) return json({ error: "The selected time zone is not valid." }, 400);
  if (emailEnabled && (!EMAIL_PATTERN.test(recipientEmail) || recipientEmail.length > 254)) {
    return json({ error: "A valid recipient email address is required when email reminders are enabled." }, 400);
  }
  if (!notificationEnabled && !emailEnabled) return json({ error: "Enable notifications or email for each countdown." }, 400);

  const count = await env.DB.prepare("SELECT COUNT(*) AS total FROM reminders WHERE device_id = ?")
    .bind(deviceId).first<{ total: number }>();
  const existing = await env.DB.prepare("SELECT id FROM reminders WHERE id = ? AND device_id = ?")
    .bind(id, deviceId).first<{ id: string }>();
  if (!existing && (count?.total || 0) >= 100) return json({ error: "This installation has reached the 100-countdown limit." }, 400);

  const result = await env.DB.prepare(`
    INSERT INTO reminders (
      id, device_id, title, target_date, recipient_email, reminder_time, timezone,
      notification_enabled, email_enabled, active, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
    ON CONFLICT(id) DO UPDATE SET
      title = excluded.title,
      target_date = excluded.target_date,
      recipient_email = excluded.recipient_email,
      reminder_time = excluded.reminder_time,
      timezone = excluded.timezone,
      notification_enabled = excluded.notification_enabled,
      email_enabled = excluded.email_enabled,
      active = 1,
      updated_at = excluded.updated_at
    WHERE reminders.device_id = excluded.device_id
  `).bind(
    id, deviceId, title, targetDate, recipientEmail, reminderTime, timezone,
    notificationEnabled ? 1 : 0, emailEnabled ? 1 : 0, new Date().toISOString()
  ).run();
  if ((result.meta?.changes || 0) === 0 && !existing) return json({ error: "Could not save this countdown." }, 409);
  return json({ saved: true, id });
}

async function deleteReminder(deviceId: string, id: string, env: Env): Promise<Response> {
  await env.DB.prepare("DELETE FROM email_sends WHERE reminder_id = ? AND EXISTS (SELECT 1 FROM reminders WHERE id = ? AND device_id = ?)")
    .bind(id, id, deviceId).run();
  await env.DB.prepare("DELETE FROM reminders WHERE id = ? AND device_id = ?").bind(id, deviceId).run();
  return json({ deleted: true });
}

async function apiRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: JSON_HEADERS });
  if (path === "/api/health" && request.method === "GET") return json({ ok: true, service: "T-MINUS", version: "1.0.0" });
  if (path === "/api/register" && request.method === "POST") return registerDevice(request, env);

  const deviceId = await authenticate(request, env);
  if (!deviceId) return json({ error: "Device not registered. Reopen T-MINUS and try again." }, 401);

  if (path === "/api/reminders" && request.method === "GET") {
    const result = await env.DB.prepare(`
      SELECT id, title, target_date AS targetDate, recipient_email AS recipientEmail,
             reminder_time AS reminderTime, timezone, notification_enabled AS notificationEnabled,
             email_enabled AS emailEnabled, active
      FROM reminders WHERE device_id = ? ORDER BY target_date ASC
    `).bind(deviceId).all();
    return json({ reminders: result.results || [] });
  }
  if (path === "/api/reminders/upsert" && request.method === "POST") {
    try { return await upsertReminder(deviceId, await readJson(request), env); }
    catch (error) { return json({ error: error instanceof Error ? error.message : "Invalid request." }, 400); }
  }
  const deleteMatch = path.match(/^\/api\/reminders\/([A-Za-z0-9_-]{6,64})$/);
  if (deleteMatch && request.method === "DELETE") return deleteReminder(deviceId, deleteMatch[1], env);
  if (path === "/api/gmail/status" && request.method === "GET") return gmailStatus(deviceId, env);
  if (path === "/api/gmail/connect" && request.method === "POST") {
    try { return await startGmailConnect(deviceId, env); }
    catch (error) { return json({ error: error instanceof Error ? error.message : "Could not start Gmail connection." }, 500); }
  }
  if (path === "/api/gmail/disconnect" && request.method === "POST") return disconnectGmail(deviceId, env);
  return json({ error: "Endpoint not found." }, 404);
}

function localClock(date: Date, timezone: string): { date: string; minutes: number } {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone,
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hourCycle: "h23"
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    date: `${values.year}-${values.month}-${values.day}`,
    minutes: Number(values.hour) * 60 + Number(values.minute)
  };
}

function dateDifference(targetDate: string, localDate: string): number {
  const target = Date.parse(`${targetDate}T00:00:00Z`);
  const current = Date.parse(`${localDate}T00:00:00Z`);
  return Math.round((target - current) / 86_400_000);
}

function utf8Base64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function utf8Base64Url(value: string): string {
  return utf8Base64(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function refreshAccessToken(refreshToken: string, env: Env): Promise<string> {
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      refresh_token: refreshToken,
      grant_type: "refresh_token"
    })
  });
  const data = await response.json() as { access_token?: string; error?: string; error_description?: string };
  if (!response.ok || !data.access_token) {
    throw new Error(data.error_description || data.error || "Google could not refresh the Gmail connection.");
  }
  return data.access_token;
}

async function sendGmail(accessToken: string, recipient: string, subject: string, body: string): Promise<void> {
  const mime = [
    `To: ${recipient}`,
    `Subject: =?UTF-8?B?${utf8Base64(subject)}?=`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=UTF-8",
    "Content-Transfer-Encoding: base64",
    "",
    (utf8Base64(body).match(/.{1,76}/g) || [""]).join("\r\n")
  ].join("\r\n");
  const response = await fetch("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", {
    method: "POST",
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify({ raw: utf8Base64Url(mime) })
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(`Gmail API returned HTTP ${response.status}: ${message.slice(0, 300)}`);
  }
}

async function runEmailReminders(env: Env): Promise<void> {
  const result = await env.DB.prepare(`
    SELECT r.id, r.device_id, r.title, r.target_date, r.recipient_email,
           r.reminder_time, r.timezone, r.email_enabled,
           g.email, g.refresh_token_ciphertext, g.refresh_token_iv
    FROM reminders r
    INNER JOIN gmail_accounts g ON g.device_id = r.device_id
    WHERE r.email_enabled = 1 AND r.active = 1
  `).all<ReminderRow & GmailRow>();

  for (const reminder of result.results || []) {
    try {
      const now = new Date();
      const clock = localClock(now, reminder.timezone);
      if (clock.date > reminder.target_date) continue;
      const [hour, minute] = reminder.reminder_time.split(":").map(Number);
      if (clock.minutes < hour * 60 + minute) continue;

      const existing = await env.DB.prepare("SELECT status, attempts, updated_at FROM email_sends WHERE reminder_id = ? AND send_date = ?")
        .bind(reminder.id, clock.date).first<{ status: string; attempts: number; updated_at: string }>();
      if (existing?.status === "sent" || (existing?.attempts || 0) >= 2) continue;

      if (existing) {
        // Do not let two overlapping cron invocations claim the same send at once.
        const updatedMillis = Date.parse(existing.updated_at);
        const recentlyClaimed = existing.status === "sending" && Number.isFinite(updatedMillis) && Date.now() - updatedMillis < 10 * 60 * 1000;
        if (recentlyClaimed) continue;
        const claim = await env.DB.prepare(`
          UPDATE email_sends
          SET status = 'sending', attempts = attempts + 1, last_error = NULL, updated_at = ?
          WHERE reminder_id = ? AND send_date = ? AND status = ? AND attempts = ? AND updated_at = ?
        `).bind(now.toISOString(), reminder.id, clock.date, existing.status, existing.attempts, existing.updated_at).run();
        if ((claim.meta?.changes || 0) === 0) continue;
      } else {
        const claim = await env.DB.prepare(`
          INSERT OR IGNORE INTO email_sends (reminder_id, send_date, status, attempts, updated_at)
          VALUES (?, ?, 'sending', 1, ?)
        `).bind(reminder.id, clock.date, now.toISOString()).run();
        if ((claim.meta?.changes || 0) === 0) continue;
      }

      const gmailRow: GmailRow = {
        email: reminder.email,
        refresh_token_ciphertext: reminder.refresh_token_ciphertext,
        refresh_token_iv: reminder.refresh_token_iv
      };
      const refreshToken = await decryptRefreshToken(gmailRow, env);
      const accessToken = await refreshAccessToken(refreshToken, env);
      const remaining = dateDifference(reminder.target_date, clock.date);
      const when = remaining === 0 ? "today" : remaining === 1 ? "in 1 day" : `in ${remaining} days`;
      const subject = `T-MINUS: ${reminder.title} ${when}`;
      const body = [
        "T-MINUS  /  DAILY COUNTDOWN REPORT",
        "",
        `COUNTDOWN: ${reminder.title}`,
        `TARGET DATE: ${reminder.target_date}`,
        `TIME REMAINING: ${remaining === 0 ? "TODAY" : `${remaining} days`}`,
        "",
        "This is your scheduled daily reminder from T-MINUS.",
        "To change or stop this reminder, edit the countdown in the T-MINUS app."
      ].join("\n");
      await sendGmail(accessToken, reminder.recipient_email, subject, body);
      await env.DB.prepare("UPDATE email_sends SET status = 'sent', last_error = NULL, updated_at = ? WHERE reminder_id = ? AND send_date = ?")
        .bind(new Date().toISOString(), reminder.id, clock.date).run();
    } catch (error) {
      const now = new Date().toISOString();
      const errorText = (error instanceof Error ? error.message : "Unknown mail error").slice(0, 500);
      // A failed send can retry once during the same local day. The send log prevents a daily duplicate after success.
      const clock = (() => { try { return localClock(new Date(), reminder.timezone).date; } catch { return "unknown"; } })();
      await env.DB.prepare("UPDATE email_sends SET status = 'failed', last_error = ?, updated_at = ? WHERE reminder_id = ? AND send_date = ?")
        .bind(errorText, now, reminder.id, clock).run();
    }
  }
}

export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/oauth/callback" && request.method === "GET") return oauthCallback(request, env);
    try {
      return await apiRequest(request, env);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Unexpected backend error." }, 500);
    }
  },

  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(runEmailReminders(env));
  }
};
