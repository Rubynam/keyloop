-- Fails (non-zero exit) if any Spec03 table does not hold exactly 100 rows.
DO $$
DECLARE
    t text;
    n bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['dealerships', 'service_types', 'service_bays', 'technicians',
                             'technician_skills', 'technician_vehicle_types', 'customers',
                             'vehicles', 'appointments', 'outbox_events'] LOOP
        EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
        IF n <> 100 THEN
            RAISE EXCEPTION '% has % rows, expected 100', t, n;
        END IF;
    END LOOP;
END $$;

SELECT status, count(*) FROM appointments GROUP BY status ORDER BY status;
SELECT processed_at IS NULL AS pending, count(*) FROM outbox_events GROUP BY 1 ORDER BY 1;
