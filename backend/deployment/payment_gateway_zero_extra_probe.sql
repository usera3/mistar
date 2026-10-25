-- Only a temporary clone is populated; live payment orders and bills are untouched.
BEGIN;
CREATE TEMP TABLE zero_extra_probe
  (LIKE public.payment_orders INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
DO $probe$
DECLARE rejected text; expected_zero boolean;
BEGIN
    SELECT position('>= 0' in pg_get_constraintdef(oid))>0 INTO expected_zero
      FROM pg_constraint WHERE conrelid='public.payment_orders'::regclass
        AND conname='payment_orders_max_extra_snapshot_positive';
    BEGIN
        INSERT INTO zero_extra_probe (
          id,public_trade_no,merchant_id,out_trade_no,alipay_account_id,payment_type,subject,merchant_param,
          original_amount_cents,actual_amount_cents,extra_amount_cents,state,state_version,request_fingerprint,
          notify_url,return_url,max_order_amount_cents_snapshot,max_extra_amount_cents_snapshot,created_at,expires_at,updated_at)
        VALUES ('11111111-1111-4111-8111-111111111111','schema-probe-trade','22222222-2222-4222-8222-222222222222',
          'schema-only-zero-extra-probe','33333333-3333-4333-8333-333333333333','alipay','Synthetic schema check','synthetic',
          990,990,0,'CREATED',0,decode(repeat('00',32),'hex'),'https://merchant.example/notify','https://merchant.example/return',
          8800,0,now(),now()+interval '5 minutes',now());
        IF NOT expected_zero THEN RAISE EXCEPTION 'Old constraint unexpectedly allowed zero'; END IF;
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS rejected=CONSTRAINT_NAME;
        IF expected_zero OR rejected NOT LIKE '%max_extra_snapshot_positive' THEN RAISE; END IF;
    END;
    IF expected_zero THEN
        BEGIN
            UPDATE zero_extra_probe SET max_extra_amount_cents_snapshot=-1;
            RAISE EXCEPTION 'Negative snapshot unexpectedly accepted';
        EXCEPTION WHEN check_violation THEN NULL;
        END;
        UPDATE zero_extra_probe SET max_extra_amount_cents_snapshot=100;
        BEGIN
            UPDATE zero_extra_probe SET original_amount_cents=0,actual_amount_cents=0;
            RAISE EXCEPTION 'Nonpositive purchase amount unexpectedly accepted';
        EXCEPTION WHEN check_violation THEN NULL;
        END;
    END IF;
END
$probe$;
SELECT json_build_object('passed',true,'test','temporary clone of real order schema',
  'zero_accepted',(SELECT count(*)=1 FROM zero_extra_probe),'production_orders_created',0);
ROLLBACK;
