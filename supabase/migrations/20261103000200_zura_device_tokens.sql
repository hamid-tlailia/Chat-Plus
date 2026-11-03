-- FCM device tokens. One row per device token; it belongs to the account currently signed in on that device.
create table public.device_tokens (
  token      text primary key check (char_length(token) between 20 and 4096),
  user_id    uuid not null references public.profiles(id) on delete cascade,
  platform   text not null default 'android',
  updated_at timestamptz not null default now()
);
create index device_tokens_user_idx on public.device_tokens(user_id);
alter table public.device_tokens enable row level security;
-- No client policies: only the RPCs below (and the push edge function, with the service role) touch it.

create or replace function public.register_device(p_token text) returns void
language sql security definer set search_path = '' as $$
  insert into public.device_tokens (token, user_id) values (p_token, auth.uid())
  on conflict (token) do update set user_id = excluded.user_id, updated_at = now();
$$;

create or replace function public.unregister_device(p_token text) returns void
language sql security definer set search_path = '' as $$
  delete from public.device_tokens where token = p_token and user_id = auth.uid();
$$;

revoke execute on function public.register_device(text), public.unregister_device(text) from public, anon;
grant execute on function public.register_device(text), public.unregister_device(text) to authenticated;
