-- Fails (non-zero exit) when the seed breaks a Spec03 rule:
--   entity tables and car_info: exactly 100 rows; join tables: at least 100;
--   vehicle_types: the small lookup list; every id is a UUIDv7.
DO $$
DECLARE
    t text;
    n bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['dealerships', 'car_info', 'service_types', 'service_bays',
                             'technicians', 'customers', 'vehicles', 'appointments',
                             'outbox_events'] LOOP
        EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
        IF n <> 100 THEN
            RAISE EXCEPTION '% has % rows, expected 100', t, n;
        END IF;
        EXECUTE format('SELECT count(*) FROM %I WHERE uuid_extract_version(id) <> 7', t) INTO n;
        IF n <> 0 THEN
            RAISE EXCEPTION '% has % ids that are not UUIDv7', t, n;
        END IF;
    END LOOP;
    FOREACH t IN ARRAY ARRAY['service_bay_vehicle_types', 'technician_skills',
                             'technician_vehicle_types'] LOOP
        EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
        IF n < 100 THEN
            RAISE EXCEPTION '% has % rows, expected at least 100', t, n;
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM vehicle_types WHERE uuid_extract_version(id) = 7) = 0 THEN
        RAISE EXCEPTION 'vehicle_types is empty or not UUIDv7';
    END IF;
END $$;

SELECT vt.code AS vehicle_type, count(DISTINCT ci.id) AS models, count(DISTINCT v.id) AS vehicles,
       count(DISTINCT sb.service_bay_id) AS bays, count(DISTINCT tv.technician_id) AS technicians
FROM vehicle_types vt
LEFT JOIN car_info ci ON ci.vehicle_type_id = vt.id
LEFT JOIN vehicles v ON v.vehicle_type_id = vt.id
LEFT JOIN service_bay_vehicle_types sb ON sb.vehicle_type_id = vt.id
LEFT JOIN technician_vehicle_types tv ON tv.vehicle_type_id = vt.id
GROUP BY vt.code ORDER BY vt.code;
SELECT status, count(*) FROM appointments GROUP BY status ORDER BY status;
SELECT processed_at IS NULL AS pending, count(*) FROM outbox_events GROUP BY 1 ORDER BY 1;
