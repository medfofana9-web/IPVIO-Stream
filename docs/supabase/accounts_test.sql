-- Isolation and PIN checks for accounts.sql (T-007). Run in the Supabase SQL editor.
-- Everything happens in one block that ends with an exception, so nothing is kept (two throwaway users,
-- several simulated device sessions). The error text lists the results; every line must read as expected:
--   1/2 true, 3 format, 4 ok, 5 the addon link, 6/7 denied, 8 1, 9 denied, 10 0, 11 no_profile, 12 0, 13 denied,
--   14 denied, 15 locked/60, 16 locked, 17 true, 18 1, 19 1, 20 the addon link, 21 true, 22 false, 23 0, 24 0,
--   25 revoked, 26 the 10-profile message.
-- Last run 2026-11-15: all as expected.
do $t$
declare
  ua uuid := gen_random_uuid(); ub uuid := gen_random_uuid();
  pa0 uuid; pa1 uuid; pb0 uuid; out text := ''; n int; j jsonb; v text; i int; ok boolean;
begin
  insert into auth.users (id, instance_id, aud, role, email) values (ua, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'fof-test-a@example.invalid'),
    (ub, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'fof-test-b@example.invalid');
  -- device sA of account A
  perform set_config('request.jwt.claims', json_build_object('sub', ua, 'role', 'authenticated', 'session_id', 'sA', 'amr', '[]'::json)::text, true);
  perform set_config('role', 'authenticated', true);
  insert into fof_profiles (slot, name) values (0, 'Med') returning id into pa0;
  insert into fof_profiles (slot, name) values (1, 'Naya') returning id into pa1;
  j := fof_set_pin(pa0, '1234'); out := out || ' 1.setpinA0=' || (j->>'ok');
  j := fof_set_pin(pa1, '5678'); out := out || ' 2.setpinA1=' || (j->>'ok');
  j := fof_set_pin(pa1, '12'); out := out || ' 3.badformat=' || (j->>'err');
  insert into fof_profile_data (profile_id, data) values (pa1, '{"x":1}');
  out := out || ' 4.A1write=ok';
  perform fof_register_device('11111111-1111-1111-1111-111111111111', 'Living room', 'Fire TV', '1.4');
  perform fof_secret_put('addons', '["https://addon.example/manifest.json"]');
  select value into v from fof_secrets_get() where name = 'addons'; out := out || ' 5.secret=' || coalesce(v, 'null');
  begin perform count(*) from fof_profile_pins; out := out || ' 6.pins=READABLE(BAD)'; exception when insufficient_privilege then out := out || ' 6.pins=denied'; end;
  begin perform count(*) from fof_account_secrets; out := out || ' 7.secrets=READABLE(BAD)'; exception when insufficient_privilege then out := out || ' 7.secrets=denied'; end;
  -- account B
  perform set_config('request.jwt.claims', json_build_object('sub', ub, 'role', 'authenticated', 'session_id', 'sB')::text, true);
  insert into fof_profiles (slot, name) values (0, 'Bob') returning id into pb0;
  select count(*) into n from fof_profiles; out := out || ' 8.B_sees_profiles=' || n;
  begin insert into fof_profile_data (profile_id, data) values (pa0, '{}'); out := out || ' 9.B_writes_A=ALLOWED(BAD)';
  exception when others then out := out || ' 9.B_writes_A=denied'; end;
  select count(*) into n from fof_secrets_get(); out := out || ' 10.B_secrets=' || n;
  j := fof_verify_pin(pa0, '1234'); out := out || ' 11.B_pin_on_A=' || (j->>'err');
  -- second device sA2 of account A
  perform set_config('request.jwt.claims', json_build_object('sub', ua, 'role', 'authenticated', 'session_id', 'sA2', 'amr', '[]'::json)::text, true);
  perform fof_register_device('22222222-2222-2222-2222-222222222222', 'Bedroom', 'Android TV', '1.4');
  select count(*) into n from fof_profile_data; out := out || ' 12.A2_reads_locked=' || n;
  begin insert into fof_profile_data (profile_id, data) values (pa0, '{}'); out := out || ' 13.A2_write_locked=ALLOWED(BAD)';
  exception when others then out := out || ' 13.A2_write_locked=denied'; end;
  j := fof_set_pin(pa0, '9999'); out := out || ' 14.A2_change_other_pin=' || (j->>'err');
  for i in 1..5 loop j := fof_verify_pin(pa0, '0000'); end loop; out := out || ' 15.after5wrong=' || (j->>'err') || '/' || (j->>'wait_s');
  j := fof_verify_pin(pa0, '1234'); out := out || ' 16.right_while_locked=' || (j->>'err');
  j := fof_verify_pin(pa1, '5678'); out := out || ' 17.A2_open_A1=' || (j->>'ok');
  select count(*) into n from fof_profile_data; out := out || ' 18.A2_reads_A1=' || n;
  update fof_profile_data set data = '{"x":2}' where profile_id = pa1; get diagnostics n = row_count; out := out || ' 19.A2_updates_A1=' || n;
  select value into v from fof_secrets_get() where name = 'addons'; out := out || ' 20.A2_addons=' || coalesce(v, 'null');
  -- forgotten PIN: fresh password sign-in can reset another profile's PIN
  perform set_config('request.jwt.claims', json_build_object('sub', ua, 'role', 'authenticated', 'session_id', 'sA2', 'amr', json_build_array(json_build_object('method', 'password', 'timestamp', extract(epoch from now())::bigint)))::text, true);
  j := fof_set_pin(pa0, '4321'); out := out || ' 21.reset_with_password=' || (j->>'ok');
  -- device sA signs out sA2 remotely
  perform set_config('request.jwt.claims', json_build_object('sub', ua, 'role', 'authenticated', 'session_id', 'sA', 'amr', '[]'::json)::text, true);
  perform fof_revoke_device('22222222-2222-2222-2222-222222222222');
  perform set_config('request.jwt.claims', json_build_object('sub', ua, 'role', 'authenticated', 'session_id', 'sA2', 'amr', '[]'::json)::text, true);
  select fof_session_ok() into ok; out := out || ' 22.revoked_session_ok=' || ok;
  select count(*) into n from fof_profiles; out := out || ' 23.revoked_sees_profiles=' || n;
  select count(*) into n from fof_secrets_get(); out := out || ' 24.revoked_secrets=' || n;
  j := fof_register_device('22222222-2222-2222-2222-222222222222', 'x', 'x', 'x'); out := out || ' 25.revoked_reregister=' || j::text;
  -- profile limit
  perform set_config('request.jwt.claims', json_build_object('sub', ua, 'role', 'authenticated', 'session_id', 'sA', 'amr', '[]'::json)::text, true);
  begin for i in 2..10 loop insert into fof_profiles (slot, name) values (i, 'P' || i); end loop; out := out || ' 26.eleven=ALLOWED(BAD)';
  exception when others then out := out || ' 26.limit=' || left(sqlerrm, 40); end;
  perform set_config('role', 'postgres', true);
  raise exception 'RESULTS:%', out;
end $t$;
