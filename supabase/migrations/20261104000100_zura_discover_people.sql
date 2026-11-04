-- "People on Zura": registered users you can invite (not yourself, not existing contacts, not
-- people who blocked you). Returns only public profile fields; newest members first.
create or replace function public.discover_profiles()
returns table (id uuid, username text, display_name text, avatar_path text, relation text)
language sql stable security definer set search_path = '' as $$
  select p.id, p.username::text, p.display_name, p.avatar_path,
         case
           when exists (select 1 from public.invitations i where i.status = 'pending' and i.from_id = auth.uid() and i.to_id = p.id) then 'pending_out'
           when exists (select 1 from public.invitations i where i.status = 'pending' and i.to_id = auth.uid() and i.from_id = p.id) then 'pending_in'
           else 'none'
         end
    from public.profiles p
   where auth.uid() is not null
     and p.id <> auth.uid()
     and not exists (select 1 from public.contacts c where c.owner_id = auth.uid() and c.peer_id = p.id)
     and not exists (select 1 from public.contacts c where c.owner_id = p.id and c.peer_id = auth.uid() and c.blocked)
   order by p.created_at desc
   limit 50;
$$;
revoke execute on function public.discover_profiles() from public, anon;
grant execute on function public.discover_profiles() to authenticated;
