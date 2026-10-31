-- Wipes every Spec03 table so the seed can be re-run. Domain data only:
-- Flyway history and the batch schema are not touched.
TRUNCATE appointments, outbox_events, technician_skills, technician_vehicle_types,
         service_bay_vehicle_types, vehicles, car_info, vehicle_types, customers,
         technicians, service_bays, service_types, dealerships;
