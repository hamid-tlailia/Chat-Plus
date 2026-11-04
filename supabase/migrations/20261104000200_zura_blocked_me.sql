-- Who blocked ME: lets the app show "You can't send messages to this person" instead of
-- failing silently. Returns only the ids of people who blocked the caller (nothing else).
create or replace function public.blocked_me() returns setof uuid
language sql stable security definer set search_path = '' as $$
  select owner_id from public.contacts where peer_id = auth.uid() and blocked
$$;
revoke all on function public.blocked_me() from public, anon;
grant execute on function public.blocked_me() to authenticated;
