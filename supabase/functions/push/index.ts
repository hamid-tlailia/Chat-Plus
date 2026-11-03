// Wakes a contact's phone through Firebase Cloud Messaging (HTTP v1).
//
// Privacy: the push never contains message content. "msg" is a bare wake-up — the phone then pulls
// its end-to-end-encrypted inbox from Supabase and decrypts locally. "call" carries only an E2EE
// sealed call invite (opaque to Google and to us).
//
// Abuse protection: caller must be signed in (verify_jwt) and an accepted, non-blocked contact
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

const KINDS = new Set(["msg", "call", "call_end"]);
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

// ───────────────────────── handler
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: auth } = await admin.auth.getUser(jwt);
  const me = auth?.user?.id;
  if (!me) return json({ error: "unauthorized" }, 401);

  let body: { to?: string; kind?: string; data?: Record<string, string> };
  try { body = await req.json(); } catch { return json({ error: "bad json" }, 400); }
  const { to, kind } = body;
  const data = body.data ?? {};
  if (!to || !kind || !KINDS.has(kind) || to === me) return json({ error: "bad request" }, 400);
  if (Object.values(data).some((v) => typeof v !== "string") || JSON.stringify(data).length > 3000) {
    return json({ error: "payload too large" }, 413);
  }

  // Must be mutual, non-blocked contacts.
  const { data: rows, error } = await admin.from("contacts").select("owner_id,peer_id,blocked")
    .or(`and(owner_id.eq.${me},peer_id.eq.${to}),and(owner_id.eq.${to},peer_id.eq.${me})`);
  if (error) return json({ error: "db" }, 500);
  if (!rows || rows.length !== 2 || rows.some((r) => r.blocked)) return json({ error: "forbidden" }, 403);

  if (!sa) return json({ sent: 0, configured: false });
  const { data: tokens } = await admin.from("device_tokens").select("token").eq("user_id", to);
  if (!tokens?.length) return json({ sent: 0, configured: true });

  const bearer = await accessToken();
  const isCall = kind !== "msg";
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
            priority: "HIGH",                       // bypasses Doze; required to ring a sleeping phone
            ttl: isCall ? "40s" : "86400s",         // a call invite is useless after it stops ringing
            ...(isCall ? {} : { collapse_key: `msg-${me}` }),
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
