// Issues ICE servers for Zura calls. Only signed-in users (verify_jwt) can call it.
//
// Two ways to provide a TURN relay (Supabase → Edge Functions → Secrets). Either or both:
//
//  A) Cloudflare Calls TURN (short-lived credentials minted per request):
//       CF_TURN_KEY_ID, CF_TURN_API_TOKEN
//
//  B) Any static TURN server (coturn, Metered, ExpressTURN, … — e.g. the relay already used by
//     the Hamidos web app as REACT_APP_TURN_URL / _USERNAME / _CREDENTIAL):
//       TURN_URLS        one or more turn:/turns: URLs, comma-separated
//       TURN_USERNAME
//       TURN_CREDENTIAL
//     Each host is also offered over TCP and TLS on 443, because many mobile carriers block UDP.
//
// With nothing configured, public STUN is returned (works on most, not all, networks).
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const STUN = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];
const TTL = 6 * 60 * 60;

function staticTurn(): unknown[] {
  const raw = Deno.env.get("TURN_URLS") ?? "";
  const username = Deno.env.get("TURN_USERNAME");
  const credential = Deno.env.get("TURN_CREDENTIAL");
  const given = raw.split(",").map((u) => u.trim()).filter(Boolean);
  if (!given.length || !username || !credential) return [];
  const urls = new Set(given);
  for (const u of given) {
    const host = /^turns?:([^:?]+)/.exec(u)?.[1];
    if (!host) continue;
    urls.add(`turn:${host}:80?transport=tcp`);
    urls.add(`turn:${host}:443?transport=tcp`);
    urls.add(`turns:${host}:443?transport=tcp`);
  }
  return [{ urls: [...urls], username, credential }];
}

async function cloudflareTurn(): Promise<unknown[]> {
  const keyId = Deno.env.get("CF_TURN_KEY_ID");
  const token = Deno.env.get("CF_TURN_API_TOKEN");
  if (!keyId || !token) return [];
  try {
    const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${keyId}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ttl: TTL }),
    });
    if (!r.ok) { console.error("cloudflare turn", r.status, await r.text()); return []; }
    const body = await r.json();
    return Array.isArray(body.iceServers) ? body.iceServers : [body.iceServers];
  } catch (e) {
    console.error("cloudflare turn", e);
    return [];
  }
}

Deno.serve(async (_req: Request) => {
  const relays = [...(await cloudflareTurn()), ...staticTurn()];
  return new Response(JSON.stringify({ iceServers: [...STUN, ...relays], relay: relays.length > 0, ttl: TTL }), {
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
});
