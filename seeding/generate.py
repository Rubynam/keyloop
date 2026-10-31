#!/usr/bin/env python3
"""Generate the Spec03 seed data: exactly 100 rows per table, edge cases tagged.

Deterministic (fixed ids, fixed RNG seed), so a regenerated file only changes
where this script changed. The dataset's "now" is ANCHOR: every past/future
edge case (COMPLETED in the past, PENDING outbox rows, ...) is relative to it.

    python3 seeding/generate.py      # rewrites seeding/sql/*.sql

Every appointment is checked here against the same rules the booking service
will apply (business hours, skill, vehicle-type certification, no overlap),
so the seed never contains a booking the API would reject - unless the row
is tagged as a deliberate historic exception.
"""
import json
import random
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ANCHOR = datetime(2026, 10, 31, 9, 0, tzinfo=timezone.utc)
ROWS = 100
OUT = Path(__file__).resolve().parent / "sql"
UTC = timezone.utc
CAR, TRUCK, EV = "PRIVATE_CAR", "TRUCK", "EV"
rng = random.Random(3)


def uid(kind, n):
    """Readable fixed UUIDs: first digit = table, last digits = row number."""
    return f"{kind}0000000-0000-4000-8000-{n:012d}"


LONG_200 = ("Keyloop Authorised Multi-Brand Service, Repair, Diagnostics, "
            "Tyre, Glass and EV High-Voltage Centre of Excellence for the "
            "Greater Saigon South Region, District 7 Riverside Branch No.01")
LONG_200 = (LONG_200 + " " + "X" * 200)[:200]
assert len(LONG_200) == 200


# ---------------------------------------------------------------- dealerships
FEATURED_DEALERS = [
    # name, timezone, open, close, edge note
    ("Keyloop Service Quận 7", "Asia/Ho_Chi_Minh", "08:00", "17:00",
     "main hub: all three pools, most bays and technicians"),
    ("Keyloop Manhattan", "America/New_York", "07:00", "19:00",
     "US DST ends 2026-11-01: UTC offset changes inside the data"),
    ("Keyloop London Park Royal", "Europe/London", "08:00", "18:00",
     "UTC+0 in winter: local time = UTC"),
    ("Keyloop Kathmandu", "Asia/Kathmandu", "09:00", "17:00",
     "offset +05:45, not a whole hour"),
    ("Keyloop Lord Howe Island", "Australia/Lord_Howe", "08:00", "16:00",
     "DST moves the clock by only 30 minutes"),
    ("Keyloop Kiritimati", "Pacific/Kiritimati", "08:00", "16:00",
     "UTC+14: local date is one day ahead of UTC"),
    ("Keyloop Pago Pago", "Pacific/Pago_Pago", "08:00", "16:00",
     "UTC-11: in the afternoon the UTC date is already tomorrow"),
    ("Keyloop 24/7 Roadside Hub", "UTC", "00:00", "24:00",
     "open all day: close_time = 24:00"),
    ("Keyloop Express Kiosk", "Asia/Singapore", "09:00", "10:00",
     "open 60 min only: shorter than most services"),
    ("Keyloop EV Centre Oslo", "Europe/Oslo", "08:00", "16:00",
     "EV pool only"),
    ("Keyloop Truck Depot Rotterdam", "Europe/Amsterdam", "06:00", "22:00",
     "TRUCK pool only, long day"),
    ("Keyloop Staff Only Workshop", "Asia/Tokyo", "09:00", "18:00",
     "technicians but no bays: never bookable"),
    ("Keyloop Empty Bays Sydney", "Australia/Sydney", "08:00", "17:00",
     "bays but no technicians: never bookable"),
    ("Keyloop Closed for Renovation", "Europe/Berlin", "08:00", "17:00",
     "every bay and technician inactive"),
    ("O'Brien & Sons Motors — Đà Nẵng", "Asia/Ho_Chi_Minh", "07:30", "16:30",
     "apostrophe, ampersand, em dash, Vietnamese letters; opens at :30"),
    (LONG_200, "Asia/Ho_Chi_Minh", "08:00", "17:00",
     "name at max length (200 chars)"),
    ("Keyloop Thủ Đức Early Bird", "Asia/Ho_Chi_Minh", "06:00", "14:00",
     "opens at 23:00 UTC of the previous day"),
    ("Keyloop Auckland", "Pacific/Auckland", "07:00", "15:30",
     "southern-hemisphere DST (UTC+13 in November)"),
    ("Keyloop Honolulu", "Pacific/Honolulu", "07:00", "16:00",
     "no DST, UTC-10"),
]

GENERIC_CITIES = [
    ("Hà Nội", "Asia/Ho_Chi_Minh"), ("Hải Phòng", "Asia/Ho_Chi_Minh"),
    ("Cần Thơ", "Asia/Ho_Chi_Minh"), ("Bangkok", "Asia/Bangkok"),
    ("Jakarta", "Asia/Jakarta"), ("Manila", "Asia/Manila"),
    ("Seoul", "Asia/Seoul"), ("Mumbai", "Asia/Kolkata"),
    ("Dubai", "Asia/Dubai"), ("Paris", "Europe/Paris"),
    ("Madrid", "Europe/Madrid"), ("Milan", "Europe/Rome"),
    ("Warsaw", "Europe/Warsaw"), ("Dublin", "Europe/Dublin"),
    ("Chicago", "America/Chicago"), ("Denver", "America/Denver"),
    ("Los Angeles", "America/Los_Angeles"), ("Toronto", "America/Toronto"),
    ("São Paulo", "America/Sao_Paulo"), ("Mexico City", "America/Mexico_City"),
    ("Johannesburg", "Africa/Johannesburg"), ("Cairo", "Africa/Cairo"),
    ("Perth", "Australia/Perth"), ("Adelaide", "Australia/Adelaide"),
]
GENERIC_HOURS = [("07:00", "17:00"), ("08:00", "18:00"), ("07:30", "16:30"),
                 ("08:15", "17:45"), ("09:00", "21:00")]

dealers = []
for i, (name, tz, op, cl, edge) in enumerate(FEATURED_DEALERS, 1):
    dealers.append(dict(n=i, name=name, tz=tz, open=op, close=cl, edge=edge))
city_count = {}
while len(dealers) < ROWS:
    i = len(dealers) + 1
    city, tz = GENERIC_CITIES[(i - 1) % len(GENERIC_CITIES)]
    city_count[city] = city_count.get(city, 0) + 1
    op, cl = GENERIC_HOURS[i % len(GENERIC_HOURS)]
    edge = None
    if i >= 50:
        edge = "no bays and no technicians yet (new branch)"
    dealers.append(dict(n=i, name=f"Keyloop {city} {city_count[city]:02d}",
                        tz=tz, open=op, close=cl, edge=edge))
for d in dealers:
    d["id"] = uid(1, d["n"])
D = {d["n"]: d for d in dealers}


# ------------------------------------------------------------------ service bays
# dealer -> [(vehicle_type, count, how many of them inactive)]
BAY_PLAN = {
    1: [(CAR, 8, 1), (TRUCK, 4, 0), (EV, 4, 1)],
    2: [(CAR, 5, 0), (TRUCK, 2, 0), (EV, 2, 0)],
    **{n: [(CAR, 2, 0), (EV, 1, 0)] for n in range(3, 8)},
    8: [(CAR, 3, 0), (TRUCK, 1, 0), (EV, 1, 0)],
    9: [(CAR, 1, 0)],
    10: [(EV, 4, 0)],
    11: [(TRUCK, 4, 0)],
    13: [(CAR, 2, 0), (EV, 1, 0)],
    14: [(CAR, 2, 2), (TRUCK, 1, 1)],
    15: [(CAR, 2, 0)],
    16: [(CAR, 1, 0)],
    17: [(CAR, 2, 0), (EV, 1, 0)],
    18: [(CAR, 2, 0)],
    19: [(CAR, 2, 0)],
    **{n: [(CAR, 1, 0)] for n in range(20, 50)},
}
BAY_PREFIX = {CAR: "Bay", TRUCK: "Heavy Bay", EV: "EV Bay"}

bays = []
for dn, plan in BAY_PLAN.items():
    for vt, count, inactive in plan:
        for k in range(1, count + 1):
            bays.append(dict(dealer=dn, type=vt, active=k <= count - inactive,
                             name=f"{BAY_PREFIX[vt]} {k:02d}", edge=None))
for i, b in enumerate(bays, 1):
    b["n"], b["id"] = i, uid(2, i)
assert len(bays) == ROWS, len(bays)


def bay(dn, vt, k):
    return next(b for b in bays if b["dealer"] == dn and b["type"] == vt
                and b["name"].endswith(f"{k:02d}"))


bay(1, CAR, 8)["edge"] = "inactive bay that still has COMPLETED history"
bay(1, EV, 4)["edge"] = "inactive EV bay: EV pool at hub has only 3 usable bays"
bay(2, CAR, 1)["edge"] = "same name 'Bay 01' as other dealerships (unique per dealership only)"
bay(15, CAR, 1)["name"] = "Khoang sửa chữa 01"
bay(15, CAR, 1)["edge"] = "Vietnamese bay name"
bay(9, CAR, 1)["edge"] = "only bay of the kiosk: one booking fills the day"
for b in bays:
    if b["dealer"] == 14:
        b["edge"] = "inactive (dealership closed for renovation)"


# ----------------------------------------------------------------- service types
SERVICE_TYPES = [
    # code, name, minutes, edge
    ("OIL_CHANGE", "Oil and filter change", 60, None),
    ("TYRE_ROTATION", "Tyre rotation", 30, None),
    ("MAJOR_SERVICE", "Major periodic service", 180, None),
    ("DIAGNOSTICS", "Diagnostics scan", 45, None),
    ("BRAKE_SERVICE", "Brake pads and discs", 90, None),
    ("EV_BATTERY_CHECK", "EV high-voltage battery check", 120, None),
    ("TRUCK_AXLE_SERVICE", "Heavy truck axle service", 240, None),
    ("AIR_CON_REGAS", "Air-con regas", 50, "50 min: not a multiple of a 15-min slot step"),
    ("WHEEL_ALIGNMENT", "Wheel alignment", 25, "25 min: not a multiple of a 15-min slot step"),
    ("TIMING_BELT", "Timing belt replacement", 95, "95 min: ends at :35"),
    ("DASHBOARD_LIGHT_RESET", "Dashboard warning light reset", 1, "minimum duration (1 min)"),
    ("ENGINE_REBUILD", "Engine rebuild", 600, "10 h: longer than most opening windows"),
    ("FULL_DAY_FLEET_INSPECTION", "Full-day fleet inspection", 1440,
     "maximum duration (24 h): fits only the 24/7 dealership"),
    ("HYDROGEN_FUEL_CELL_SERVICE", "Hydrogen fuel-cell service", 240,
     "no technician has this skill: never bookable"),
    ("WINDSCREEN_REPLACEMENT", "Windscreen replacement", 120, None),
    ("BATTERY_12V_REPLACEMENT", "12V battery replacement", 20, None),
    ("PRE_PURCHASE_INSPECTION", "Pre-purchase inspection", 75, None),
    ("SOFTWARE_UPDATE_OTA", "Software update (workshop)", 30, None),
]
AREAS = ["FRONT_SUSPENSION", "REAR_SUSPENSION", "STEERING", "EXHAUST", "COOLING",
         "FUEL_SYSTEM", "TRANSMISSION", "CLUTCH", "LIGHTING", "WIPERS",
         "SEATBELTS", "AIRBAGS", "DOOR_LOCKS", "SUNROOF", "INFOTAINMENT",
         "PARKING_SENSORS", "CAMERAS", "HV_CABLES", "CHARGING_PORT", "TOW_HITCH"]
KINDS = [("INSPECT", 30), ("REPAIR", 90), ("REPLACE", 120), ("CALIBRATE", 45), ("CLEAN", 15)]
for kind, minutes in KINDS:
    for area in AREAS:
        if len(SERVICE_TYPES) == ROWS:
            break
        SERVICE_TYPES.append((f"{kind}_{area}",
                              f"{kind.title()} {area.replace('_', ' ').lower()}",
                              minutes, None))
services = [dict(n=i, id=uid(4, i), code=c, name=nm, minutes=m, edge=e)
            for i, (c, nm, m, e) in enumerate(SERVICE_TYPES, 1)]
S = {s["code"]: s for s in services}
assert len(services) == ROWS


# ------------------------------------------------------------------ technicians
FIRST = ["An", "Bình", "Chi", "Dũng", "Hà", "Khoa", "Lan", "Minh", "Ngọc", "Phúc",
         "Quân", "Thảo", "Tuấn", "Vy", "James", "Olivia", "Liam", "Emma", "Noah",
         "Ava", "Lucas", "Mia", "Kenji", "Yuki", "Priya", "Arjun", "Fatima", "Omar",
         "Sven", "Ingrid", "Mateo", "Lucía", "Chloé", "Jürgen"]
LAST = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Vũ", "Smith", "Johnson", "Brown",
        "García", "Müller", "Sato", "Tanaka", "Patel", "Khan", "Hansen", "Rossi",
        "Dubois", "Kowalski", "Reyes"]


def person_name(i):
    return f"{FIRST[i % len(FIRST)]} {LAST[(i * 7) % len(LAST)]}"


# dealer -> [(certs, active, skills, name or None, edge)]
TECH_PLAN = {
    1: [
        ({CAR, TRUCK, EV}, True, ["OIL_CHANGE", "TYRE_ROTATION", "MAJOR_SERVICE", "DIAGNOSTICS",
                                  "BRAKE_SERVICE", "EV_BATTERY_CHECK", "TRUCK_AXLE_SERVICE",
                                  "AIR_CON_REGAS", "WHEEL_ALIGNMENT", "TIMING_BELT",
                                  "DASHBOARD_LIGHT_RESET", "ENGINE_REBUILD"],
         "Nguyễn Thị Ánh Tuyết", "all-rounder: 12 skills, certified for all 3 vehicle types"),
        ({CAR}, True, ["OIL_CHANGE", "TYRE_ROTATION", "MAJOR_SERVICE"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "DIAGNOSTICS"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "TYRE_ROTATION", "BRAKE_SERVICE"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "TYRE_ROTATION", "TIMING_BELT"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "TYRE_ROTATION", "AIR_CON_REGAS"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "TYRE_ROTATION", "WHEEL_ALIGNMENT"], "Prince",
         "single-word name (mononym)"),
        ({TRUCK}, True, ["TRUCK_AXLE_SERVICE", "OIL_CHANGE"], None, None),
        ({TRUCK}, True, ["TRUCK_AXLE_SERVICE", "OIL_CHANGE"], "Seán Ó Briain-MacDonald",
         "accents, space and hyphen in surname"),
        ({EV}, True, ["EV_BATTERY_CHECK", "TYRE_ROTATION"], "李伟",
         "CJK name"),
        ({EV}, True, ["EV_BATTERY_CHECK", "TYRE_ROTATION"], None, None),
        ({CAR}, False, ["OIL_CHANGE", "MAJOR_SERVICE"], None,
         "inactive (left the company) but has COMPLETED history"),
        ({CAR}, True, [], None, "new hire: no skills yet -> never picked"),
        (set(), True, ["OIL_CHANGE", "DIAGNOSTICS"], None,
         "has skills but no vehicle-type certification -> never picked"),
    ],
    2: [({CAR}, True, ["OIL_CHANGE", "MAJOR_SERVICE"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "TYRE_ROTATION"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "BRAKE_SERVICE"], None, None),
        ({CAR}, True, ["OIL_CHANGE", "DIAGNOSTICS"], None, None),
        ({CAR}, True, ["OIL_CHANGE"], None, None),
        ({TRUCK}, True, ["TRUCK_AXLE_SERVICE"], None, None),
        ({TRUCK}, True, ["TRUCK_AXLE_SERVICE"], None, None),
        ({EV}, True, ["EV_BATTERY_CHECK"], None, None)],
    **{n: [({CAR}, True, ["OIL_CHANGE"], None, None),
           ({CAR}, True, ["TYRE_ROTATION"], None, None),
           ({EV}, True, ["EV_BATTERY_CHECK"], None, None)] for n in range(3, 8)},
    8: [({CAR}, True, ["OIL_CHANGE", "DIAGNOSTICS", "FULL_DAY_FLEET_INSPECTION"], None,
         "only technician able to do the 24 h service"),
        ({CAR}, True, ["OIL_CHANGE", "ENGINE_REBUILD"], None, None),
        ({TRUCK}, True, ["TRUCK_AXLE_SERVICE"], None, None),
        ({EV}, True, ["EV_BATTERY_CHECK"], None, None)],
    9: [({CAR}, True, ["TYRE_ROTATION", "WHEEL_ALIGNMENT", "DASHBOARD_LIGHT_RESET",
                       "BATTERY_12V_REPLACEMENT"], None, "only technician of the kiosk")],
    10: [({EV}, True, ["EV_BATTERY_CHECK"], None, None)] * 3,
    11: [({TRUCK}, True, ["TRUCK_AXLE_SERVICE"], None, None)] * 3,
    12: [({CAR}, True, ["OIL_CHANGE"], None, "works at a dealership with no bays")] * 3,
    14: [({CAR}, False, ["OIL_CHANGE"], None, "inactive (dealership closed)"),
         (set(), False, ["DIAGNOSTICS"], None, "inactive and no certification")],
    15: [({CAR}, True, ["OIL_CHANGE"], None, None)] * 2,
    16: [({CAR}, True, ["OIL_CHANGE"], LONG_200.replace("Keyloop", "Technician", 1)[:200],
          "name at max length (200 chars)")],
    17: [({CAR}, True, ["OIL_CHANGE", "TYRE_ROTATION"], None, None),
         ({EV}, True, ["EV_BATTERY_CHECK"], None, None)],
    18: [({CAR}, True, ["OIL_CHANGE"], None, None)] * 2,
    19: [({CAR}, True, ["OIL_CHANGE"], None, None)] * 2,
    **{n: [({CAR}, True, [], None, None)] for n in range(20, 58)},
}

techs = []
for dn, plan in TECH_PLAN.items():
    for certs, active, skills, name, edge in plan:
        i = len(techs) + 1
        techs.append(dict(n=i, id=uid(3, i), dealer=dn, certs=set(certs), active=active,
                          skills=list(skills), name=name or person_name(i), edge=edge))
assert len(techs) == ROWS, len(techs)
for t in techs:
    if t["dealer"] >= 20:
        t["edge"] = "no skills: generic branch not staffed for any service yet"
        break


def tech(dn, k):
    return [t for t in techs if t["dealer"] == dn][k - 1]


skill_rows = [(t, S[c]) for t in techs for c in t["skills"]]
cert_rows = [(t, vt) for t in techs for vt in sorted(t["certs"])]
# Pad / trim to exactly 100 rows with extra skills for Manhattan + Thủ Đức
extra_skills = [(tech(2, 5), "TYRE_ROTATION"), (tech(2, 5), "DIAGNOSTICS"),
                (tech(17, 1), "DIAGNOSTICS"), (tech(18, 1), "TYRE_ROTATION"),
                (tech(19, 1), "TYRE_ROTATION"), (tech(15, 2), "TYRE_ROTATION")]
while len(skill_rows) < ROWS:
    t, c = extra_skills.pop(0)
    t["skills"].append(c)
    skill_rows.append((t, S[c]))
assert len(skill_rows) == ROWS, len(skill_rows)
assert len(cert_rows) == ROWS, len(cert_rows)


# -------------------------------------------------------------------- customers
CUSTOMER_EDGES = [
    # name, email, phone, edge
    ("Trần Minh Khoa", "khoa.tran@example.com", "+84901234567", None),
    ("Lê Thị Hồng", None, "+84912345678", "no email, phone only"),
    ("Oliver Bennett", "oliver.bennett@example.co.uk", None, "no phone, email only"),
    ("Nguyễn Văn Ánh", "anh.nguyen@example.vn", "+84987654321", "Vietnamese diacritics"),
    ("Siobhán O'Neill", "siobhan.oneill@example.ie", "+353861234567", "apostrophe in name"),
    ("An Trần", "an.tran+keyloop@example.com", "+84933333333", "plus-addressed email"),
    ("Mixed Case", "MIXED.Case@Example.COM", "+14155550123",
     "upper-case email (uniqueness is case-insensitive)"),
    (LONG_200.replace("Keyloop", "Customer", 1)[:200], "long.name@example.com", "+6591234567",
     "name at max length (200 chars)"),
    ("Max Phone", "max.phone@example.com", "+861380013800012", "phone at E.164 max (15 digits)"),
    ("No Vehicle Yet", "no.vehicle@example.com", "+84944444444", "customer with no vehicle"),
    ("Hoàng Fleet Owner", "fleet.hoang@example.vn", "+84955555555", "owns 6 vehicles of all types"),
    ("Công ty TNHH Vận tải Sài Gòn", "dispatch@vantaisaigon.example.vn", "+842838123456",
     "company as customer, truck fleet"),
    ("Prince", "prince@example.com", None, "single-word name"),
    ("山田 太郎", "taro.yamada@example.jp", "+819012345678", "CJK name"),
    ("Zoë 🚗 Lambert", "zoe.lambert@example.fr", "+33612345678",
     "emoji (4-byte UTF-8) in name"),
    ("Ravi Kumar", "ravi.kumar@example.in", "+919812345678", "only past appointments"),
]
customers = []
for i in range(1, ROWS + 1):
    if i <= len(CUSTOMER_EDGES):
        name, email, phone, edge = CUSTOMER_EDGES[i - 1]
    else:
        name, edge = person_name(i + 3), None
        local = name.lower().replace(" ", ".").replace("'", "")
        email = f"{local}.{i}@example.com"
        phone = f"+849{rng.randrange(10**7, 10**8)}" if i % 9 else None
        if i % 13 == 0:
            email, edge = None, "no email"
    customers.append(dict(n=i, id=uid(5, i), name=name, email=email, phone=phone, edge=edge))
C = {c["n"]: c for c in customers}


# --------------------------------------------------------------------- vehicles
VIN_CHARS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"  # ISO 3779: no I, O, Q
CAR_MODELS = [("Toyota", "Vios"), ("Honda", "Civic"), ("Mazda", "CX-5"), ("Ford", "Ranger"),
              ("Hyundai", "Accent"), ("Kia", "Seltos"), ("Volkswagen", "Golf"),
              ("BMW", "320i"), ("Mercedes-Benz", "C 200"), ("Citroën", "C3 Aircross")]
TRUCK_MODELS = [("Isuzu", "QKR 270"), ("Hino", "300 Series"), ("Volvo", "FH16"),
                ("Scania", "R 450"), ("Hyundai", "Mighty EX8")]
EV_MODELS = [("VinFast", "VF 8"), ("Tesla", "Model 3"), ("Nissan", "Leaf"),
             ("Hyundai", "IONIQ 5"), ("Citroën", "ë-C4")]
VEHICLE_EDGES = [
    # customer, make, model, year, type, vin or None, edge
    (1, "Toyota", "Camry", 2022, CAR, None, None),
    (11, "Ford", "Mustang", 1965, CAR, None, "classic car (1965)"),
    (11, "VinFast", "VF 9", 2027, EV, None, "next model year (2027)"),
    (11, "Ford", "F-150 Lightning", 2025, EV, None,
     "electric pickup: classed EV, not TRUCK (needs a HV bay)"),
    (11, "Isuzu", "D-Max", 2020, TRUCK, None, None),
    (11, "Rolls-Royce", "Phantom", 2024, CAR, None, "hyphenated make"),
    (11, "Citroën", "ë-C4", 2023, EV, None, "non-ASCII make and model"),
    (2, "Honda", "Wave", 2019, CAR, "11111111111111111", "VIN of digits only (valid format)"),
    (3, "Mazda", "3", 2021, CAR, None, "one-character model"),
]
vehicles = []
for cust, make, model, year, vt, vin, edge in VEHICLE_EDGES:
    vehicles.append(dict(customer=cust, make=make, model=model, year=year, type=vt, vin=vin, edge=edge))
for k in range(5):
    make, model = TRUCK_MODELS[k]
    vehicles.append(dict(customer=12, make=make, model=model, year=2018 + k, type=TRUCK, vin=None,
                         edge="truck fleet of one company" if k == 0 else None))
owner = 4
while len(vehicles) < ROWS:
    if owner == 10:  # stays without a vehicle
        owner += 1
    if owner in (11, 12):
        owner = 13
    i = len(vehicles)
    vt = EV if i % 7 == 0 else TRUCK if i % 11 == 0 else CAR
    make, model = rng.choice({CAR: CAR_MODELS, TRUCK: TRUCK_MODELS, EV: EV_MODELS}[vt])
    vehicles.append(dict(customer=owner, make=make, model=model, year=rng.randrange(2008, 2027),
                         type=vt, vin=None, edge=None))
    owner += 1
seen_vins = set()
for i, v in enumerate(vehicles, 1):
    v["n"], v["id"] = i, uid(6, i)
    if v["vin"] is None:
        v["vin"] = "".join(rng.choice(VIN_CHARS) for _ in range(17))
    assert v["vin"] not in seen_vins
    seen_vins.add(v["vin"])
V = {v["n"]: v for v in vehicles}
assert len(vehicles) == ROWS
for c in customers:
    if c["edge"] is None and not any(v["customer"] == c["n"] for v in vehicles):
        c["edge"] = "customer with no vehicle"


def vehicle_of(cust, vt=CAR):
    """First vehicle of that type owned by customer `cust`, or by the next customer who has one."""
    return next(v for c in range(cust, ROWS + cust) for v in vehicles
                if v["customer"] == (c - 1) % ROWS + 1 and v["type"] == vt)


# ----------------------------------------------------------------- appointments
appointments = []


def parse_hm(hm):
    h, m = map(int, hm.split(":"))
    return h, m


def local_bounds(dealer, day):
    tz = ZoneInfo(dealer["tz"])
    oh, om = parse_hm(dealer["open"])
    ch, cm = parse_hm(dealer["close"])
    opening = datetime.combine(day, time(oh, om), tz)
    closing = (datetime.combine(day + timedelta(days=1), time(0), tz) if ch == 24
               else datetime.combine(day, time(ch, cm), tz))
    return opening, closing


def overlaps(a, b):
    return a["start"] < b["end"] and b["start"] < a["end"]


def conflict(new):
    for a in appointments:
        if a["status"] == "CANCELLED" or new["status"] == "CANCELLED":
            continue
        if overlaps(a, new) and (a["tech"] is new["tech"] or a["bay"] is new["bay"]
                                 or a["vehicle"] is new["vehicle"]):
            return a
    return None


def book(dealer_n, vehicle, service_code, tech_, bay_, day, hm, status="CONFIRMED",
         created=None, updated=None, idem=None, edge=None, historic=False, moved_from=None,
         check=True):
    """Add one appointment after checking the booking rules.

    historic=True skips the "resource must be active now" rule: the row is
    history from before the technician / bay was deactivated.
    """
    dealer, svc = D[dealer_n], S[service_code]
    h, m = parse_hm(hm)
    start = datetime.combine(day, time(h, m), ZoneInfo(dealer["tz"]))
    end = start + timedelta(minutes=svc["minutes"])
    a = dict(dealer=dealer, vehicle=vehicle, customer=C[vehicle["customer"]], service=svc,
             tech=tech_, bay=bay_, start=start.astimezone(UTC), end=end.astimezone(UTC),
             status=status, idem=idem, edge=edge, moved_from=moved_from)
    opening, closing = local_bounds(dealer, day)
    problems = []
    if not (opening <= start and end <= closing):
        problems.append("outside business hours")
    if tech_["dealer"] != dealer_n or bay_["dealer"] != dealer_n:
        problems.append("resource from another dealership")
    if bay_["type"] != vehicle["type"]:
        problems.append("bay not in vehicle pool")
    if vehicle["type"] not in tech_["certs"]:
        problems.append("technician not certified")
    if service_code not in tech_["skills"]:
        problems.append("technician not skilled")
    if not historic and not (tech_["active"] and bay_["active"]):
        problems.append("inactive resource")
    if status == "COMPLETED" and a["end"] > ANCHOR:
        problems.append("COMPLETED in the future")
    if conflict(a):
        problems.append(f"overlaps {conflict(a)['start']}")
    if problems:
        if check:
            raise ValueError(f"bad seed appointment {edge or ''} {start}: {problems}")
        return None
    # Booked some time before the start, never after "now"
    a["created"] = created or min(a["start"] - timedelta(days=rng.randrange(1, 21),
                                                         minutes=rng.randrange(0, 600)),
                                  ANCHOR - timedelta(minutes=rng.randrange(60, 60 * 24 * 20)))
    a["updated"] = updated or a["created"]
    if a["status"] in ("CANCELLED", "COMPLETED") and updated is None:
        a["updated"] = min(max(a["created"], a["end"] if status == "COMPLETED" else a["start"]
                               - timedelta(hours=rng.randrange(1, 48))), ANCHOR)
        a["updated"] = max(a["updated"], a["created"])
    appointments.append(a)
    return a


d = date
HUB = 1
car1, ev1, truck1 = vehicle_of(1), vehicle_of(11, EV), vehicle_of(12, TRUCK)

# --- overlap rules (D1)
book(HUB, car1, "OIL_CHANGE", tech(1, 2), bay(1, CAR, 1), d(2026, 11, 3), "08:00",
     idem="7f1c2b8e-1d2a-4c55-9b0e-3a7d9e5f0c11",
     edge="starts exactly at opening time (08:00)")
book(HUB, vehicle_of(4), "OIL_CHANGE", tech(1, 2), bay(1, CAR, 1), d(2026, 11, 3), "09:00",
     edge="back-to-back with previous row: same technician and bay, end == start ([) range)")
book(HUB, vehicle_of(5), "OIL_CHANGE", tech(1, 2), bay(1, CAR, 1), d(2026, 11, 3), "08:30",
     status="CANCELLED", edge="CANCELLED row overlapping two CONFIRMED rows on same tech/bay (allowed)")
book(HUB, vehicle_of(6), "MAJOR_SERVICE", tech(1, 2), bay(1, CAR, 2), d(2026, 11, 3), "14:00",
     edge="ends exactly at closing time (17:00)")
for k, hm in enumerate(["10:00", "10:00"]):
    book(HUB, vehicle_of(7), "BRAKE_SERVICE", tech(1, 4), bay(1, CAR, 3), d(2026, 11, 4), hm,
         status="CANCELLED", idem=f"retry-{k}",
         edge="same slot cancelled twice by the same customer")
book(HUB, vehicle_of(7), "BRAKE_SERVICE", tech(1, 4), bay(1, CAR, 3), d(2026, 11, 4), "10:00",
     edge="re-booked the exact slot of the two cancelled rows above")
# EV pool at the hub fully booked 10:00-12:00 (3 active EV bays)
for k, (veh, tk) in enumerate([(ev1, tech(1, 10)), (vehicle_of(28, EV), tech(1, 11)),
                               (vehicle_of(11, EV), tech(1, 1))]):
    if k == 2:
        veh = [v for v in vehicles if v["customer"] == 11 and v["type"] == EV][1]
    book(HUB, veh, "EV_BATTERY_CHECK", tk, bay(1, EV, k + 1), d(2026, 11, 5), "10:00",
         edge="EV pool full 10:00-12:00 (all 3 active EV bays busy); car pool still free")
book(HUB, vehicle_of(4), "OIL_CHANGE", tech(1, 3), bay(1, CAR, 4), d(2026, 11, 6), "10:00",
     edge="same vehicle, back-to-back services (1/2)")
book(HUB, vehicle_of(4), "TYRE_ROTATION", tech(1, 5), bay(1, CAR, 5), d(2026, 11, 6), "11:00",
     edge="same vehicle, back-to-back services (2/2), other tech and bay")
fleet = [v for v in vehicles if v["customer"] == 12]
for k in range(3):
    book(11, fleet[k], "TRUCK_AXLE_SERVICE", tech(11, k + 1), bay(11, TRUCK, k + 1),
         d(2026, 11, 9), "08:00",
         edge="one customer, 3 trucks at the same time (overlap per customer is allowed)")
book(HUB, truck1 if False else fleet[3], "TRUCK_AXLE_SERVICE", tech(1, 8), bay(1, TRUCK, 1),
     d(2026, 11, 10), "13:00", edge="TRUCK in truck bay with truck-certified technician")

# --- durations
book(HUB, vehicle_of(6), "DASHBOARD_LIGHT_RESET", tech(1, 1), bay(1, CAR, 6), d(2026, 11, 10),
     "08:00", edge="1-minute service: ends 08:01")
book(HUB, vehicle_of(13), "TIMING_BELT", tech(1, 5), bay(1, CAR, 1), d(2026, 11, 10), "09:00",
     edge="95-minute service: ends at 10:35, off the 15-min grid")
book(HUB, vehicle_of(14), "AIR_CON_REGAS", tech(1, 6), bay(1, CAR, 2), d(2026, 11, 10), "10:35",
     edge="starts off-grid (10:35) right after an off-grid end")
book(8, vehicle_of(15), "FULL_DAY_FLEET_INSPECTION", tech(8, 1), bay(8, CAR, 1),
     d(2026, 11, 12), "00:00", edge="24 h service: 00:00 to 24:00 at the 24/7 hub")
book(1, vehicle_of(16), "ENGINE_REBUILD", tech(1, 1), bay(1, CAR, 7), d(2026, 11, 16), "08:00",
     check=False)  # 600 min does not fit 08:00-17:00: must be rejected
assert not any(a["service"]["code"] == "ENGINE_REBUILD" and a["dealer"]["n"] == 1
               for a in appointments)
book(8, vehicle_of(16), "ENGINE_REBUILD", tech(8, 2), bay(8, CAR, 2), d(2026, 11, 16), "08:00",
     edge="10 h job: only fits a dealership open long enough")

# --- time zones and calendar
book(2, vehicle_of(17), "OIL_CHANGE", tech(2, 1), bay(2, CAR, 1), d(2026, 10, 31), "07:00",
     edge="later today (2 h after 'now'); 07:00 EDT = 11:00 UTC, day before DST ends")
book(2, vehicle_of(18), "OIL_CHANGE", tech(2, 1), bay(2, CAR, 1), d(2026, 11, 1), "07:00",
     edge="07:00 EST = 12:00 UTC: DST ended this night, 25-hour day")
book(2, vehicle_of(19), "MAJOR_SERVICE", tech(2, 1), bay(2, CAR, 2), d(2026, 12, 31), "16:00",
     edge="ends 19:00 EST on 31 Dec = 00:00 UTC on 1 Jan 2027 (year boundary in UTC)")
book(17, vehicle_of(20), "OIL_CHANGE", tech(17, 1), bay(17, CAR, 1), d(2026, 11, 5), "06:00",
     edge="local 5 Nov 06:00 = UTC 4 Nov 23:00: cache date must be the local date")
book(6, vehicle_of(21), "OIL_CHANGE", tech(6, 1), bay(6, CAR, 1), d(2026, 11, 5), "08:00",
     edge="UTC+14: local 5 Nov = UTC 4 Nov 18:00")
book(7, vehicle_of(22), "OIL_CHANGE", tech(7, 1), bay(7, CAR, 1), d(2026, 11, 5), "15:00",
     edge="UTC-11: local 5 Nov 15:00 = UTC 6 Nov 02:00")
book(6, vehicle_of(23), "OIL_CHANGE", tech(6, 1), bay(6, CAR, 2), d(2026, 11, 5), "09:00",
     edge="same instant on a UTC clock as the Pago Pago row would be a different local day")
book(4, vehicle_of(24), "OIL_CHANGE", tech(4, 1), bay(4, CAR, 1), d(2026, 11, 5), "09:00",
     edge="+05:45 offset: starts 03:15 UTC")
book(5, vehicle_of(25), "OIL_CHANGE", tech(5, 1), bay(5, CAR, 1), d(2026, 10, 1), "08:00",
     status="COMPLETED", edge="Lord Howe before DST (+10:30)")
book(5, vehicle_of(25), "OIL_CHANGE", tech(5, 1), bay(5, CAR, 1), d(2026, 11, 5), "08:00",
     edge="Lord Howe after DST (+11): same local time, UTC moved by 30 min")
book(18, vehicle_of(26), "OIL_CHANGE", tech(18, 1), bay(18, CAR, 1), d(2026, 11, 5), "07:00",
     edge="Auckland +13: local 5 Nov 07:00 = UTC 4 Nov 18:00")
book(8, vehicle_of(27), "DIAGNOSTICS", tech(8, 1), bay(8, CAR, 3), d(2026, 11, 13), "23:15",
     edge="ends exactly at 24:00 (next day 00:00): touches only one local date")
book(HUB, vehicle_of(29), "OIL_CHANGE", tech(1, 3), bay(1, CAR, 3), d(2028, 2, 29), "10:00",
     created=ANCHOR - timedelta(days=2), edge="leap day 2028-02-29")
book(HUB, vehicle_of(30), "MAJOR_SERVICE", tech(1, 2), bay(1, CAR, 4), d(2027, 10, 29), "08:00",
     created=ANCHOR - timedelta(hours=5), edge="one year ahead: outside a 14-day search range")
book(3, vehicle_of(31), "OIL_CHANGE", tech(3, 1), bay(3, CAR, 1), d(2026, 11, 2), "08:00",
     edge="London in winter: local = UTC")

# --- small or empty capacity
book(9, vehicle_of(32), "TYRE_ROTATION", tech(9, 1), bay(9, CAR, 1), d(2026, 11, 4), "09:00",
     edge="kiosk 1/3: fills the only bay 09:00-09:30")
book(9, vehicle_of(33), "WHEEL_ALIGNMENT", tech(9, 1), bay(9, CAR, 1), d(2026, 11, 4), "09:30",
     edge="kiosk 2/3: 09:30-09:55")
book(9, vehicle_of(34), "DASHBOARD_LIGHT_RESET", tech(9, 1), bay(9, CAR, 1), d(2026, 11, 4),
     "09:59", edge="kiosk 3/3: last minute 09:59-10:00, day is now fully booked")
book(10, vehicle_of(35, EV), "EV_BATTERY_CHECK", tech(10, 1),
     bay(10, EV, 1), d(2026, 11, 6), "08:00", edge="EV-only dealership")
book(15, vehicle_of(36), "OIL_CHANGE", tech(15, 1), bay(15, CAR, 1), d(2026, 11, 7), "07:30",
     edge="dealership name with quotes/unicode; opens at :30")

# --- history and lifecycle
book(HUB, vehicle_of(37), "OIL_CHANGE", tech(1, 12), bay(1, CAR, 8), d(2026, 9, 15), "09:00",
     status="COMPLETED", historic=True,
     edge="COMPLETED by a technician who is now inactive, in a bay now inactive")
book(HUB, vehicle_of(16), "OIL_CHANGE", tech(1, 2), bay(1, CAR, 1), d(2026, 10, 20), "09:00",
     edge="still CONFIRMED although it ended in the past (job never closed)")
a = book(HUB, vehicle_of(38), "DIAGNOSTICS", tech(1, 3), bay(1, CAR, 2), d(2026, 10, 30),
         "16:15", status="COMPLETED", edge="booked 5 minutes before it started")
a["created"] = a["start"] - timedelta(minutes=5)
a["updated"] = a["end"]
book(HUB, vehicle_of(39), "OIL_CHANGE", tech(1, 2), bay(1, CAR, 5), d(2026, 12, 1), "08:00",
     created=datetime(2025, 11, 1, 2, 0, tzinfo=UTC),
     edge="booked 13 months in advance")
book(HUB, vehicle_of(40), "BRAKE_SERVICE", tech(1, 4), bay(1, CAR, 4), d(2026, 11, 20), "13:00",
     created=ANCHOR - timedelta(days=10), updated=ANCHOR - timedelta(days=1),
     moved_from=d(2026, 11, 3), edge="rescheduled from 3 Nov: updated_at > created_at")
book(HUB, vehicle_of(41), "OIL_CHANGE", tech(1, 6), bay(1, CAR, 6), d(2026, 11, 25), "08:00",
     status="CANCELLED", edge="CANCELLED in the future")
book(HUB, vehicle_of(42), "OIL_CHANGE", tech(1, 6), bay(1, CAR, 6), d(2026, 11, 2), "08:00",
     created=ANCHOR - timedelta(minutes=3), idem="k" * 128,
     edge="idempotency key at max length (128); booked 3 min before 'now' -> outbox PENDING")
book(16, vehicle_of(16, None) if False else vehicle_of(43), "OIL_CHANGE", tech(16, 1),
     bay(16, CAR, 1), d(2026, 11, 3), "08:00", edge="dealership and technician names at 200 chars")
book(HUB, vehicle_of(15), "OIL_CHANGE", tech(1, 2), bay(1, CAR, 2), d(2026, 10, 2), "08:00",
     status="COMPLETED", edge="customer 15 (emoji name)")
for k in range(3):
    book(HUB, vehicle_of(16), "OIL_CHANGE", tech(1, 3), bay(1, CAR, 3),
         d(2026, 9, 1) + timedelta(days=30 * k), "08:00", status="COMPLETED",
         edge="customer with only past appointments" if k == 0 else None)

# --- filler: ordinary bookings on ordinary days, random but valid
POOL_SERVICE = {CAR: ["OIL_CHANGE", "TYRE_ROTATION", "MAJOR_SERVICE", "DIAGNOSTICS",
                      "BRAKE_SERVICE", "AIR_CON_REGAS", "WHEEL_ALIGNMENT"],
                TRUCK: ["TRUCK_AXLE_SERVICE"], EV: ["EV_BATTERY_CHECK", "TYRE_ROTATION"]}
bookable = [n for n in range(1, 20) if n not in (9, 12, 13, 14)]
attempts = 0
while len(appointments) < ROWS:
    attempts += 1
    assert attempts < 10000
    dn = rng.choice([1, 1, 1, 2, 2, 3] + bookable)
    veh = rng.choice(vehicles)
    candidates = [b for b in bays if b["dealer"] == dn and b["type"] == veh["type"] and b["active"]]
    if not candidates:
        continue
    code = rng.choice(POOL_SERVICE[veh["type"]])
    staff = [t for t in techs if t["dealer"] == dn and t["active"] and code in t["skills"]
             and veh["type"] in t["certs"]]
    if not staff:
        continue
    day = d(2026, 9, 1) + timedelta(days=rng.randrange(0, 110))
    oh, om = parse_hm(D[dn]["open"])
    slot = oh * 60 + om + 15 * rng.randrange(0, 40)
    hm = f"{slot // 60:02d}:{slot % 60:02d}"
    if slot >= 24 * 60:
        continue
    probe = datetime.combine(day, time(slot // 60, slot % 60), ZoneInfo(D[dn]["tz"]))
    past = probe.astimezone(UTC) + timedelta(minutes=S[code]["minutes"]) <= ANCHOR
    status = (rng.choice(["COMPLETED"] * 4 + ["CANCELLED"]) if past
              else rng.choice(["CONFIRMED"] * 5 + ["CANCELLED"]))
    book(dn, veh, code, rng.choice(staff), rng.choice(candidates), day, hm, status=status,
         idem=str(rng.getrandbits(64)) if rng.random() < 0.4 else None, check=False)

appointments.sort(key=lambda a: (a["start"], a["dealer"]["n"]))
for i, a in enumerate(appointments, 1):
    a["n"], a["id"] = i, uid(7, i)
assert len(appointments) == ROWS


# ---------------------------------------------------------------- outbox events
def local_dates(a, start=None, end=None):
    """Local calendar dates an appointment touches (half-open: an end at 00:00 does not count)."""
    tz = ZoneInfo(a["dealer"]["tz"])
    first = (start or a["start"]).astimezone(tz).date()
    last = ((end or a["end"]) - timedelta(microseconds=1)).astimezone(tz).date()
    return [str(first + timedelta(days=k)) for k in range((last - first).days + 1)]


events = []


def event(agg_type, agg_id, ev_type, payload, created, edge=None):
    events.append(dict(agg_type=agg_type, agg_id=agg_id, type=ev_type, payload=payload,
                       created=created, edge=edge))
    return events[-1]


def appt_payload(a, **extra):
    return {"appointmentId": a["id"], "dealershipId": a["dealer"]["id"],
            "dates": local_dates(a), **extra}


for a in appointments:
    if a["status"] == "CANCELLED":
        event("Appointment", a["id"], "AppointmentCancelled", appt_payload(a), a["updated"])
    if a["moved_from"]:
        event("Appointment", a["id"], "AppointmentRescheduled",
              appt_payload(a, oldDates=[str(a["moved_from"])], newDates=local_dates(a)),
              a["updated"], edge="old + new dates in one event")
event("Dealership", D[8]["id"], "BusinessHoursChanged",
      {"dealershipId": D[8]["id"],
       "dates": [str(d(2026, 11, 1) + timedelta(days=k)) for k in range(60)]},
      ANCHOR - timedelta(minutes=20), edge="large payload: 60 dates")
event("ServiceBay", bay(1, EV, 4)["id"], "BayBlocked",
      {"dealershipId": D[1]["id"], "bayId": bay(1, EV, 4)["id"], "dates": ["2026-11-05"],
       "reason": "Hỏng cầu nâng — chờ linh kiện"},
      ANCHOR - timedelta(minutes=20), edge="non-ASCII text in payload; same created_at as previous")
event("Technician", tech(1, 12)["id"], "TechnicianUnavailable",
      {"dealershipId": D[1]["id"], "technicianId": tech(1, 12)["id"], "dates": []},
      ANCHOR - timedelta(days=40), edge="empty dates array")
event("Appointment", uid(7, 999), "AppointmentCancelled",
      {"appointmentId": uid(7, 999), "dealershipId": D[1]["id"], "dates": ["2026-11-03"]},
      ANCHOR - timedelta(hours=2), edge="aggregate id that no longer exists (no FK on outbox)")
for a in sorted(appointments, key=lambda a: a["created"], reverse=True):
    if len(events) == ROWS:
        break
    event("Appointment", a["id"], "AppointmentBooked", appt_payload(a), a["created"],
          edge=("payload date differs from UTC date" if a["dealer"]["n"] in (6, 7, 17, 18)
                else None))
assert len(events) == ROWS, len(events)

events.sort(key=lambda e: e["created"])
for i, e in enumerate(events, 1):
    e["n"], e["id"] = i, uid(8, i)
    age = ANCHOR - e["created"]
    e["attempts"] = 0
    if age > timedelta(hours=1):
        e["processed"] = e["created"] + timedelta(milliseconds=rng.randrange(5, 1500))
    else:
        e["processed"] = None
pending = [e for e in events if e["processed"] is None]
pending[0]["attempts"], pending[0]["edge"] = 25, "PENDING with 25 failed attempts (poison / Redis down)"
pending[1]["attempts"], pending[1]["edge"] = 3, "PENDING, retried 3 times"
old = [e for e in events if e["processed"] and ANCHOR - e["processed"] > timedelta(days=7)]
old[0]["edge"] = "PROCESSED > 7 days ago: cleanup job must delete it"
recent = [e for e in events if e["processed"] and ANCHOR - e["processed"] < timedelta(days=7)]
recent[0]["attempts"], recent[0]["edge"] = 2, "PROCESSED after 2 failed attempts"
recent[1]["processed"], recent[1]["edge"] = recent[1]["created"], "processed_at == created_at"


# -------------------------------------------------------------------------- SQL
def lit(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, datetime):
        return "'" + v.astimezone(UTC).isoformat(timespec="milliseconds") + "'"
    return "'" + str(v).replace("'", "''") + "'"


def write(fname, table, columns, rows):
    """rows: [(values tuple, edge note or None)]"""
    assert len(rows) == ROWS, (table, len(rows))
    edges = sum(1 for _, e in rows if e)
    lines = [f"-- Generated by seeding/generate.py (Spec03). Do not edit by hand.",
             f"-- {table}: {len(rows)} rows, {edges} tagged as edge cases.",
             f"INSERT INTO {table} ({', '.join(columns)}) VALUES"]
    for k, (vals, edge) in enumerate(rows):
        sep = ";" if k == len(rows) - 1 else ","
        note = f"  -- edge: {edge}" if edge else ""
        lines.append(f"    ({', '.join(lit(v) for v in vals)}){sep}{note}")
    (OUT / fname).write_text("\n".join(lines) + "\n", encoding="utf-8")


OUT.mkdir(exist_ok=True)
(OUT / "00_reset.sql").write_text(
    "-- Wipes every Spec03 table so the seed can be re-run. Domain data only:\n"
    "-- Flyway history and the batch schema are not touched.\n"
    "TRUNCATE appointments, outbox_events, technician_skills, technician_vehicle_types,\n"
    "         vehicles, customers, technicians, service_bays, service_types, dealerships;\n",
    encoding="utf-8")
write("01_dealerships.sql", "dealerships",
      ["id", "name", "timezone", "open_time", "close_time", "created_at"],
      [((x["id"], x["name"], x["tz"], x["open"], x["close"],
         datetime(2025, 1, 1, tzinfo=UTC) + timedelta(days=x["n"])), x["edge"]) for x in dealers])
write("02_service_types.sql", "service_types", ["id", "code", "name", "duration_minutes"],
      [((s["id"], s["code"], s["name"], s["minutes"]), s["edge"]) for s in services])
write("03_service_bays.sql", "service_bays", ["id", "dealership_id", "name", "vehicle_type", "active"],
      [((b["id"], D[b["dealer"]]["id"], b["name"], b["type"], b["active"]), b["edge"]) for b in bays])
write("04_technicians.sql", "technicians", ["id", "dealership_id", "name", "active"],
      [((t["id"], D[t["dealer"]]["id"], t["name"], t["active"]), t["edge"]) for t in techs])
write("05_technician_skills.sql", "technician_skills", ["technician_id", "service_type_id"],
      [((t["id"], s["id"]), f"{len(t['skills'])} skills" if t["n"] == 1 and s["n"] == 1 else None)
       for t, s in skill_rows])
write("06_technician_vehicle_types.sql", "technician_vehicle_types",
      ["technician_id", "vehicle_type"],
      [((t["id"], vt), "certified for all 3 types" if len(t["certs"]) == 3 and vt == CAR else None)
       for t, vt in cert_rows])
write("07_customers.sql", "customers", ["id", "name", "email", "phone", "created_at"],
      [((c["id"], c["name"], c["email"], c["phone"],
         datetime(2025, 6, 1, tzinfo=UTC) + timedelta(hours=7 * c["n"])), c["edge"])
       for c in customers])
write("08_vehicles.sql", "vehicles", ["id", "customer_id", "vin", "make", "model", "year", "vehicle_type"],
      [((v["id"], C[v["customer"]]["id"], v["vin"], v["make"], v["model"], v["year"], v["type"]),
        v["edge"]) for v in vehicles])
write("09_appointments.sql", "appointments",
      ["id", "customer_id", "vehicle_id", "dealership_id", "service_type_id", "technician_id",
       "service_bay_id", "vehicle_type", "start_time", "end_time", "status", "idempotency_key",
       "created_at", "updated_at"],
      [((a["id"], a["customer"]["id"], a["vehicle"]["id"], a["dealer"]["id"], a["service"]["id"],
         a["tech"]["id"], a["bay"]["id"], a["vehicle"]["type"], a["start"], a["end"], a["status"],
         a["idem"], a["created"], a["updated"]), a["edge"]) for a in appointments])
write("10_outbox_events.sql", "outbox_events",
      ["id", "aggregate_type", "aggregate_id", "event_type", "payload", "created_at",
       "processed_at", "attempts"],
      [((e["id"], e["agg_type"], e["agg_id"], e["type"],
         json.dumps(e["payload"], ensure_ascii=False), e["created"], e["processed"],
         e["attempts"]), e["edge"]) for e in events])

print(f"wrote {len(list(OUT.glob('*.sql')))} files to {OUT}")
