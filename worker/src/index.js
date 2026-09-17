export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname !== "/api/status") {
      return new Response("Not found", { status: 404 });
    }

    try {
      const accessToken = await getAccessToken(env);
      const events = await getUpcomingEvents(accessToken, env.ZOHO_CALENDAR_UID);
      const status = computeStatus(events);

      return new Response(JSON.stringify(status), {
        headers: {
          "Content-Type": "application/json",
          "Access-Control-Allow-Origin": "*",
          "Cache-Control": "no-store",
        },
      });
    } catch (err) {
      return new Response(JSON.stringify({ error: String(err.message || err) }), {
        status: 500,
        headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
      });
    }
  },
};

const TOKEN_KEY = "zoho_access_token";

async function getAccessToken(env) {
  const cached = await env.TOKEN_CACHE.get(TOKEN_KEY);
  if (cached) {
    return cached;
  }

  const params = new URLSearchParams({
    grant_type: "refresh_token",
    client_id: env.ZOHO_CLIENT_ID,
    client_secret: env.ZOHO_CLIENT_SECRET,
    refresh_token: env.ZOHO_REFRESH_TOKEN,
  });

  const resp = await fetch(`https://accounts.zoho.eu/oauth/v2/token?${params.toString()}`, {
    method: "POST",
  });

  const data = await resp.json();

  if (!data.access_token) {
    throw new Error("Zoho token refresh failed: " + JSON.stringify(data));
  }

  await env.TOKEN_CACHE.put(TOKEN_KEY, data.access_token, { expirationTtl: 3300 });

  return data.access_token;
}

async function getUpcomingEvents(accessToken, calendarUid) {
  const now = new Date();
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  const end = new Date(now);
  end.setDate(end.getDate() + 7);
  end.setHours(23, 59, 59, 999);

  const zFmt = (d) => d.toISOString().replace(/[-:]/g, "").split(".")[0] + "Z";

  const range = JSON.stringify({ start: zFmt(start), end: zFmt(end) });
  const eventsUrl = `https://calendar.zoho.eu/api/v1/calendars/${calendarUid}/events?range=${encodeURIComponent(range)}`;

  const resp = await fetch(eventsUrl, {
    headers: { Authorization: `Zoho-oauthtoken ${accessToken}` },
  });

  const data = await resp.json();
  return data.events || [];
}

function parseZohoDateTime(s) {
  const m = s.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z|[+-]\d{2}:?\d{2})$/);
  if (!m) return new Date(s);

  const [, y, mo, d, h, mi, se, tzRaw] = m;
  const tz = tzRaw === "Z" || tzRaw.includes(":") ? tzRaw : `${tzRaw.slice(0, 3)}:${tzRaw.slice(3)}`;

  return new Date(`${y}-${mo}-${d}T${h}:${mi}:${se}${tz}`);
}

function computeStatus(events) {
  const now = new Date();

  const parsed = events
    .filter((e) => e && e.dateandtime && e.dateandtime.start && e.dateandtime.end)
    .map((e) => ({
      title: e.title,
      organizer: e.organizer || null,
      start: parseZohoDateTime(e.dateandtime.start),
      end: parseZohoDateTime(e.dateandtime.end),
    }))
    .sort((a, b) => a.start - b.start);

  let current = null;
  let next = null;

  for (const ev of parsed) {
    if (ev.start <= now && now < ev.end) {
      current = ev;
    } else if (ev.start > now && !next) {
      next = ev;
    }
  }

  const hhmm = (d) =>
    d.toLocaleTimeString("en-GB", { timeZone: "Asia/Tbilisi", hour: "2-digit", minute: "2-digit" });

  return {
    isOccupied: !!current,
    currentMeeting: current
      ? {
          title: current.title,
          organizer: current.organizer,
          startTime: hhmm(current.start),
          endTime: hhmm(current.end),
        }
      : null,
    nextMeeting: next
      ? {
          title: next.title,
          organizer: next.organizer,
          startTime: hhmm(next.start),
          endTime: hhmm(next.end),
        }
      : null,
  };
}
