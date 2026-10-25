-- Schema-only compatibility fix for the pinned Epay gateway (2f7a9cc / Goose 23).
-- Merchant migration 14 permits zero extra; order snapshot migration 6 still rejects it.
-- Run as the gateway database owner after a private, validated backup.
-- Preserves the constraint name, NOT NULL, all monetary checks and all order data.
-- This is a deployment hotfix, not a fabricated Goose version or payment record.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '15s';
SET LOCAL search_path = public, pg_catalog;
SELECT pg_advisory_xact_lock(577330018);
DO $fix$
DECLARE definition text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO definition FROM pg_constraint
      WHERE conrelid='public.payment_orders'::regclass
        AND conname='payment_orders_max_extra_snapshot_positive';
    definition := regexp_replace(definition,'[()[:space:]]','','g');
    IF definition='CHECKmax_extra_amount_cents_snapshot>0' THEN
        ALTER TABLE public.payment_orders
          DROP CONSTRAINT payment_orders_max_extra_snapshot_positive,
          ADD CONSTRAINT payment_orders_max_extra_snapshot_positive
            CHECK (max_extra_amount_cents_snapshot >= 0);
    ELSIF definition='CHECKmax_extra_amount_cents_snapshot>=0' THEN
        NULL;
    ELSE
        RAISE EXCEPTION 'Unexpected gateway snapshot constraint; refusing an unverified change';
    END IF;
END
$fix$;
COMMIT;
