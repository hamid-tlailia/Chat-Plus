// Issues short-lived ICE servers for Zura calls. Only signed-in users (verify_jwt) can call it.
// Secrets (Supabase Dashboard → Edge Functions → Secrets):
//   CF_TURN_KEY_ID, CF_TURN_API_TOKEN  — from Cloudflare Dashboard → Calls → TURN keys.
// Without them we return public STUN only, so calls still work on most networks.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const STUN = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];
const TTL = 6 * 60 * 60; // 6 h: longer than any call, short enough to be useless if leaked

Deno.serve(async (_req: Request) => {
  const keyId = Deno.env.get("CF_TURN_KEY_ID");
  const token = Deno.env.get("CF_TURN_API_TOKEN");
  let iceServers: unknown[] = STUN;
  let relay = false;

  if (keyId && token) {
    try {
      const r = await fetch(
        `https://rtc.live.cloudflare.com/v1/turn/keys/${keyId}/credentials/generate-ice-servers`,
        {
          method: "POST",
          headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
          body: JSON.stringify({ ttl: TTL }),
        },
      );
      if (r.ok) {
        const body = await r.json();
        const servers = Array.isArray(body.iceServers) ? body.iceServers : [body.iceServers];
        iceServers = [...STUN, ...servers];
        relay = true;
      } else {
        console.error("cloudflare turn", r.status, await r.text());
      }
    } catch (e) {
      console.error("cloudflare turn", e);
    }
  }

  return new Response(JSON.stringify({ iceServers, relay, ttl: TTL }), {
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
});
