-- Zura core schema: profiles, invitations, contacts, E2EE inbox, private realtime topics, storage.
-- Apply in Supabase Dashboard → SQL Editor (project "zura"), or `supabase db push`.

create extension if not exists citext with schema extensions;

-- ───────────────────────── profiles
create table public.profiles (
  id            uuid primary key references auth.users(id) on delete cascade,
  username      extensions.citext not null unique check (username ~ '^[A-Za-z0-9_]{3,24}$'),
  display_name  text not null check (char_length(display_name) between 1 and 48),
  avatar_path   text,
  identity_key  text check (identity_key is null or char_length(identity_key) <= 200),
  created_at    timestamptz not null default now(),
  last_seen     timestamptz
);
alter table public.profiles enable row level security;

-- Creates the profile from sign-up metadata. If the username is missing/invalid/taken we fall back to
-- "user_<8 hex>" instead of failing the whole sign-up; the user can rename later. Display name falls
-- back to the username, and is clipped to the 48-char limit.
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_username text := trim(coalesce(new.raw_user_meta_data->>'username', ''));
  v_display  text := trim(coalesce(new.raw_user_meta_data->>'display_name', ''));
begin
  if v_username !~ '^[A-Za-z0-9_]{3,24}$'
     or exists (select 1 from public.profiles where username = v_username::extensions.citext) then
    v_username := 'user_' || substr(replace(new.id::text, '-', ''), 1, 8);
  end if;
  insert into public.profiles (id, username, display_name)
  values (new.id, v_username, left(coalesce(nullif(v_display, ''), v_username), 48));
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ───────────────────────── contacts (created only by accepting an invitation)
create table public.contacts (
  owner_id   uuid not null references public.profiles(id) on delete cascade,
  peer_id    uuid not null references public.profiles(id) on delete cascade,
  blocked    boolean not null default false,
  created_at timestamptz not null default now(),
  primary key (owner_id, peer_id),
  check (owner_id <> peer_id)
);
create index contacts_peer_idx on public.contacts(peer_id);
alter table public.contacts enable row level security;

-- ───────────────────────── invitations
create type public.invite_status as enum ('pending', 'accepted', 'rejected', 'cancelled');
create table public.invitations (
  id           uuid primary key default gen_random_uuid(),
  from_id      uuid not null references public.profiles(id) on delete cascade,
  to_id        uuid not null references public.profiles(id) on delete cascade,
  message      text check (message is null or char_length(message) <= 280),
  status       public.invite_status not null default 'pending',
  created_at   timestamptz not null default now(),
  responded_at timestamptz,
  check (from_id <> to_id)
);
create unique index invitations_one_pending
  on public.invitations (least(from_id, to_id), greatest(from_id, to_id)) where status = 'pending';
create index invitations_to_idx on public.invitations(to_id, status);
create index invitations_from_idx on public.invitations(from_id, status);
alter table public.invitations enable row level security;

-- ───────────────────────── helpers
create or replace function public.can_message(a uuid, b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.contacts where owner_id = a and peer_id = b and not blocked)
     and exists (select 1 from public.contacts where owner_id = b and peer_id = a and not blocked);
$$;

create or replace function public.is_related(a uuid, b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select a = b
      or exists (select 1 from public.contacts where owner_id = a and peer_id = b)
      or exists (select 1 from public.invitations
                  where (from_id = a and to_id = b) or (from_id = b and to_id = a));
$$;

-- ───────────────────────── RLS policies
create policy "profiles: self, contacts and invite partners" on public.profiles
  for select to authenticated using (public.is_related((select auth.uid()), id));
create policy "profiles: update own" on public.profiles
  for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy "contacts: read own" on public.contacts
  for select to authenticated using (owner_id = (select auth.uid()));

create policy "invitations: parties read" on public.invitations
  for select to authenticated using ((select auth.uid()) in (from_id, to_id));

-- ───────────────────────── inbox: E2EE envelopes, deleted on delivery
create table public.inbox (
  id           bigint generated always as identity primary key,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  sender_id    uuid not null default auth.uid() references public.profiles(id) on delete cascade,
  payload      text not null check (char_length(payload) <= 65536),
  created_at   timestamptz not null default now()
);
create index inbox_recipient_idx on public.inbox(recipient_id, id);
create index inbox_sender_idx on public.inbox(sender_id);
alter table public.inbox enable row level security;

create policy "inbox: send to accepted contacts" on public.inbox
  for insert to authenticated
  with check (sender_id = (select auth.uid()) and public.can_message((select auth.uid()), recipient_id));
create policy "inbox: recipient reads" on public.inbox
  for select to authenticated using (recipient_id = (select auth.uid()));
create policy "inbox: recipient acks (deletes)" on public.inbox
  for delete to authenticated using (recipient_id = (select auth.uid()));

-- ───────────────────────── RPCs
create or replace function public.username_available(p_username text) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_username ~ '^[A-Za-z0-9_]{3,24}$'
     and not exists (select 1 from public.profiles where username = p_username::extensions.citext);
$$;

create or replace function public.search_profiles(p_query text)
returns table (id uuid, username text, display_name text, avatar_path text, relation text)
language sql stable security definer set search_path = '' as $$
  select p.id, p.username::text, p.display_name, p.avatar_path,
         case
           when p.id = auth.uid() then 'self'
           when exists (select 1 from public.contacts c where c.owner_id = auth.uid() and c.peer_id = p.id) then 'contact'
           when exists (select 1 from public.invitations i where i.status = 'pending' and i.from_id = auth.uid() and i.to_id = p.id) then 'pending_out'
           when exists (select 1 from public.invitations i where i.status = 'pending' and i.to_id = auth.uid() and i.from_id = p.id) then 'pending_in'
           else 'none'
         end
    from public.profiles p
   where auth.uid() is not null
     and char_length(trim(p_query)) >= 3
     and p.username ilike replace(replace(trim(p_query), '%', ''), '_', '\_') || '%'
   order by char_length(p.username)
   limit 20;
$$;

create or replace function public.respond_invite(p_id uuid, p_accept boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare inv public.invitations;
begin
  select * into inv from public.invitations where id = p_id and to_id = auth.uid() and status = 'pending' for update;
  if not found then raise exception 'invitation not found'; end if;
  update public.invitations
     set status = case when p_accept then 'accepted'::public.invite_status else 'rejected'::public.invite_status end,
         responded_at = now()
   where id = p_id;
  if p_accept then
    insert into public.contacts (owner_id, peer_id) values (inv.from_id, inv.to_id), (inv.to_id, inv.from_id)
    on conflict (owner_id, peer_id) do update set blocked = false;
  end if;
end $$;

create or replace function public.send_invite(p_to uuid, p_message text default null) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); existing uuid; new_id uuid;
begin
  if me is null then raise exception 'not authenticated'; end if;
  if p_to = me then raise exception 'cannot invite yourself'; end if;
  if exists (select 1 from public.contacts where owner_id = me and peer_id = p_to) then
    raise exception 'already a contact';
  end if;
  if exists (select 1 from public.contacts where owner_id = p_to and peer_id = me and blocked) then
    raise exception 'cannot invite this user';
  end if;
  -- They already invited me → accept theirs instead of creating a duplicate.
  select id into existing from public.invitations where from_id = p_to and to_id = me and status = 'pending';
  if existing is not null then
    perform public.respond_invite(existing, true);
    return existing;
  end if;
  if (select count(*) from public.invitations where from_id = me and status = 'pending') >= 50 then
    raise exception 'too many pending invitations';
  end if;
  insert into public.invitations (from_id, to_id, message) values (me, p_to, nullif(trim(p_message), ''))
  returning id into new_id;
  return new_id;
end $$;

create or replace function public.cancel_invite(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.invitations set status = 'cancelled', responded_at = now()
   where id = p_id and from_id = auth.uid() and status = 'pending';
  if not found then raise exception 'invitation not found'; end if;
end $$;

create or replace function public.remove_contact(p_peer uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid();
begin
  delete from public.contacts where (owner_id = me and peer_id = p_peer) or (owner_id = p_peer and peer_id = me);
  delete from public.inbox where (sender_id = me and recipient_id = p_peer) or (sender_id = p_peer and recipient_id = me);
end $$;

create or replace function public.set_blocked(p_peer uuid, p_blocked boolean) returns void
language sql security definer set search_path = '' as $$
  update public.contacts set blocked = p_blocked where owner_id = auth.uid() and peer_id = p_peer;
$$;

-- Lock down: RPCs only for signed-in users (username check also for sign-up).
revoke execute on all functions in schema public from public, anon;
grant execute on function public.username_available(text) to anon, authenticated;
grant execute on function public.search_profiles(text), public.send_invite(uuid, text), public.respond_invite(uuid, boolean),
  public.cancel_invite(uuid), public.remove_contact(uuid), public.set_blocked(uuid, boolean),
  public.can_message(uuid, uuid), public.is_related(uuid, uuid) to authenticated;

-- ───────────────────────── realtime
alter publication supabase_realtime add table public.inbox, public.invitations, public.contacts;

-- Private broadcast topics "u:<uid>": only the owner may listen; only accepted contacts may send.
create policy "zura: listen on own topic" on realtime.messages
  for select to authenticated
  using (realtime.topic() = 'u:' || (select auth.uid())::text);
create policy "zura: send to contact topic" on realtime.messages
  for insert to authenticated
  with check (
    realtime.topic() ~ '^u:[0-9a-f-]{36}$'
    and public.can_message((select auth.uid()), substring(realtime.topic() from 3)::uuid)
  );

-- ───────────────────────── storage
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('avatars', 'avatars', false, 2097152, array['image/jpeg', 'image/webp', 'image/png']),
  ('blobs',   'blobs',   false, 52428800, null);

create policy "avatars: signed-in read" on storage.objects for select to authenticated
  using (bucket_id = 'avatars');
create policy "avatars: owner writes" on storage.objects for insert to authenticated
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "avatars: owner updates" on storage.objects for update to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "avatars: owner deletes" on storage.objects for delete to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy "blobs: owner uploads" on storage.objects for insert to authenticated
  with check (bucket_id = 'blobs' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "blobs: owner or contact reads" on storage.objects for select to authenticated
  using (bucket_id = 'blobs' and (
    (storage.foldername(name))[1] = (select auth.uid())::text
    or public.can_message((select auth.uid()), ((storage.foldername(name))[1])::uuid)));
create policy "blobs: owner deletes" on storage.objects for delete to authenticated
  using (bucket_id = 'blobs' and (storage.foldername(name))[1] = (select auth.uid())::text);
