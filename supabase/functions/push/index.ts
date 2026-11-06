// Wakes a contact's phone through Firebase Cloud Messaging (HTTP v1).
//
// Privacy: no readable content ever. "msg" may carry the message SEALED end-to-end for the
// recipient (opaque to Google and to us) so a sleeping phone can show it at once; otherwise it is a
// bare wake-up and the phone pulls its E2EE inbox. "call" carries only an E2EE sealed call invite.
//
// Abuse protection: caller must be signed in (verify_jwt — keep it ON: the id is read from the
// gateway-verified token) and an accepted, non-blocked contact
// of the recipient — the same rule the database enforces for messages.
//
// Secret (Supabase → Edge Functions → Secrets):
//   FCM_SERVICE_ACCOUNT = the full JSON of a Firebase service-account key
//   (Firebase console → Project settings → Service accounts → Generate new private key)
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

type ServiceAccount = { project_id: string; client_email: string; private_key: string };
const sa: ServiceAccount | null = (() => {
  try { return JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT") ?? "null"); } catch { return null; }
})();

// invite: "X invited you" — allowed only for a pending invitation from the caller to the recipient,
// sent in the last 10 minutes (no repeated nagging). invite_ok: "X accepted" — needs mutual contacts.
// sync: wake the app to pull its inbox without showing anything (moment reactions, history restore).
const KINDS = new Set(["msg", "call", "call_end", "invite", "invite_ok", "sync"]);
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

// ───────────────────────── Google OAuth access token (cached ~55 min)
let cached: { token: string; exp: number } | null = null;

function b64url(data: ArrayBuffer | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function accessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 300 > now) return cached.token;
  const pem = sa!.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa!.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now, exp: now + 3600,
  }));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${b64url(sig)}`,
    }),
  });
  if (!r.ok) throw new Error(`oauth ${r.status} ${await r.text()}`);
  const t = await r.json();
  cached = { token: t.access_token, exp: now + (t.expires_in ?? 3600) };
  return cached.token;
}

/** Signed-in user id from a token the gateway already verified (verify_jwt). Anon keys have no user. */
function callerId(header: string): string | null {
  try {
    const part = header.replace(/^Bearer\s+/i, "").split(".")[1] ?? "";
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
    const p = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))));
    return p.role === "authenticated" && typeof p.sub === "string" && /^[0-9a-f-]{36}$/i.test(p.sub) ? p.sub : null;
  } catch { return null; }
}

// ───────────────────────── handler
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  // verify_jwt is ON: the gateway has already checked this token's signature and expiry, so read
  // the caller's id from it directly (saves a round-trip to the auth server on every push).
  const me = callerId(req.headers.get("Authorization") ?? "");
  if (!me) return json({ error: "unauthorized" }, 401);
  // Start the Google token (cached; only a cold instance pays for it) while the database answers.
  const bearerP = sa ? accessToken() : null;
  bearerP?.catch(() => {});

  let body: { to?: string; kind?: string; data?: Record<string, string>; priority?: string };
  try { body = await req.json(); } catch { return json({ error: "bad json" }, 400); }
  const { to, kind } = body;
  const data = body.data ?? {};
  if (!to || !/^[0-9a-f-]{36}$/i.test(to) || !kind || !KINDS.has(kind) || to === me) return json({ error: "bad request" }, 400);
  if (Object.values(data).some((v) => typeof v !== "string") || JSON.stringify(data).length > 3000) {
    return json({ error: "payload too large" }, 413);
  }

  // Must be mutual, non-blocked contacts (or, for "invite", a fresh pending invitation). Both lookups
  // run at once (one round-trip, not two); nothing is sent unless the check passes.
  const isInvite = kind === "invite";
  const [{ data: rows, error }, { data: tokens }] = await Promise.all([
    isInvite
      ? admin.from("invitations").select("id").eq("from_id", me).eq("to_id", to).eq("status", "pending")
          .gte("created_at", new Date(Date.now() - 10 * 60_000).toISOString()).limit(1)
      : admin.from("contacts").select("owner_id,peer_id,blocked")
          .or(`and(owner_id.eq.${me},peer_id.eq.${to}),and(owner_id.eq.${to},peer_id.eq.${me})`),
    admin.from("device_tokens").select("token").eq("user_id", to),
  ]);
  if (error) return json({ error: "db" }, 500);
  if (isInvite) { if (!rows?.length) return json({ error: "forbidden" }, 403); }
  else if (!rows || rows.length !== 2 || rows.some((r: { blocked?: boolean }) => r.blocked)) return json({ error: "forbidden" }, 403);

  if (!sa || !bearerP) return json({ sent: 0, configured: false });
  if (!tokens?.length) return json({ sent: 0, configured: true });

  const bearer = await bearerP;
  const isCall = kind === "call" || kind === "call_end";
  let sent = 0;
  await Promise.all(tokens.map(async ({ token }) => {
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          data: { ...data, t: kind, f: me },
          android: {
            // HIGH bypasses Doze (calls always; messages for recipients who look offline).
            // NORMAL for recipients who look online: delivered promptly on an awake phone, and not
            // counted against the app when it ends without a notification (they got it live).
            // "sync" never shows a notification: NORMAL, so Android doesn't count it against us.
            priority: isCall || (kind !== "sync" && body.priority !== "normal") ? "HIGH" : "NORMAL",
            // A call invite is useless after it stops ringing. Messages are NOT collapsed: each push
            // may carry its own sealed message, shown on a locked phone right away.
            ttl: isCall ? "40s" : "86400s",
          },
        },
      }),
    });
    if (r.ok) { sent++; return; }
    const text = await r.text();
    if (r.status === 404 || text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT")) {
      await admin.from("device_tokens").delete().eq("token", token); // stale install
    } else {
      console.error("fcm", r.status, text);
    }
  }));
  return json({ sent, configured: true });
});
