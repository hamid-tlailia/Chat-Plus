-- Move RLS helper functions out of the exposed API schema so clients cannot call them directly
-- (otherwise any signed-in user could probe whether two arbitrary users are contacts).
create schema if not exists private;
grant usage on schema private to authenticated;
alter function public.can_message(uuid, uuid) set schema private;
alter function public.is_related(uuid, uuid) set schema private;
revoke all on function private.can_message(uuid, uuid), private.is_related(uuid, uuid) from public, anon;
grant execute on function private.can_message(uuid, uuid), private.is_related(uuid, uuid) to authenticated;
revoke execute on function public.handle_new_user() from public, anon, authenticated;
