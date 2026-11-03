-- Find people by username prefix (2+ chars), by display name (anywhere, any script), or by EXACT email.
-- Email is never returned and never partially matched, so it can't be used to enumerate addresses.
create or replace function public.search_profiles(p_query text)
returns table (id uuid, username text, display_name text, avatar_path text, relation text)
language sql stable security definer set search_path = '' as $$
  with q as (
    select trim(both from regexp_replace(coalesce(p_query, ''), '^@', '')) as raw
  ), esc as (
    select raw, replace(replace(replace(raw, '\', '\\'), '%', '\%'), '_', '\_') as pat from q
  )
  select p.id, p.username::text, p.display_name, p.avatar_path,
         case
           when p.id = auth.uid() then 'self'
           when exists (select 1 from public.contacts c where c.owner_id = auth.uid() and c.peer_id = p.id) then 'contact'
           when exists (select 1 from public.invitations i where i.status = 'pending' and i.from_id = auth.uid() and i.to_id = p.id) then 'pending_out'
           when exists (select 1 from public.invitations i where i.status = 'pending' and i.to_id = auth.uid() and i.from_id = p.id) then 'pending_in'
           else 'none'
         end
    from public.profiles p, esc
   where auth.uid() is not null
     and char_length(esc.raw) >= 2
     and (
          p.username::text ilike esc.pat || '%'
       or p.display_name ilike '%' || esc.pat || '%'
       or (position('@' in esc.raw) > 1 and exists (
             select 1 from auth.users u where u.id = p.id and lower(u.email) = lower(esc.raw)))
     )
   order by (lower(p.username::text) = lower(esc.raw)) desc,
            (p.username::text ilike esc.pat || '%') desc,
            char_length(p.username::text)
   limit 20;
$$;
revoke execute on function public.search_profiles(text) from public, anon;
grant execute on function public.search_profiles(text) to authenticated;
