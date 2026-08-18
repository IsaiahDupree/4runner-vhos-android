# Whole-vehicle digital-twin foundation

## Product boundary

VHOS is a versioned representation of the physical vehicle, its configuration, its evidence, and
its lifecycle. It is not an OBD dashboard and it must never translate an absence of diagnostic
trouble codes into a healthy vehicle.

The first Android implementation establishes three durable records:

1. the exact head-unit runtime and deployment capabilities;
2. an append-only 2005 Toyota 4Runner configuration history; and
3. one current, evidence-qualified assessment for every major vehicle system.

The shared JSON contracts live in `4runner-vehicle-health-os/contracts/jsonschema/v1`. Android uses
the same contract names and versions in its typed domain model and owner-controlled JSON export.

## Head-unit inventory

The app reads and records real platform values rather than hard-coding the photographed device:

- manufacturer, model, Android release/API level, security patch, and build fingerprint;
- board, hardware, `/proc/cpuinfo` descriptor when exposed, supported ABIs, and logical CPU count;
- total/available RAM and internal storage;
- full display resolution, density, and density-independent dimensions;
- BLE feature declaration and scan/connect permission state;
- notification permission, unknown-source installer authority, and battery-optimization exemption;
- VHOS package, app version, version code, and installer package.

Serial numbers and advertising addresses are intentionally excluded. Inventory revisions are
append-only and de-duplicated by a canonical hardware/runtime/capability fingerprint.

## Vehicle configuration and variant guard

`vehicle.configuration-profile@1.0.0` is append-only. The first vehicle pack is
`toyota.4runner.2005@0.1.0` and records:

- VIN;
- 4.0L V6 `1GR-FE`, 4.7L V8 `2UZ-FE`, or unknown;
- 2WD, 4WD, or unknown;
- conventional rear springs, rear air suspension, or unknown;
- trim, build date, tires, severe-use choice, and modifications; and
- current mileage, observation time, and provenance.

Timing-drive applicability is derived from engine configuration and cannot be edited separately:

| Engine | Timing drive | Rule consequence |
| --- | --- | --- |
| Unknown | Unknown | No timing-drive schedule may activate |
| 4.0L V6 `1GR-FE` | Timing chain | V8 timing-belt rules are inapplicable |
| 4.7L V8 `2UZ-FE` | Timing belt | Only source-versioned V8 timing-belt rules may activate |

The app does not mark the profile schedule-ready until VIN, engine, drivetrain, rear suspension,
trim, build date, tire configuration, severe-use choice, modification status, and mileage are all
resolved. `STOCK` is recorded explicitly rather than inferred from an empty modifications list.

## Evidence-qualified health map

The health map covers 22 systems, from engine, driveline, electrical, HVAC, brakes, steering,
suspension, and tires through body/frame corrosion, visibility, restraints, cabin equipment, and
fluids/hoses/belts/leaks.

Every assessment separates state from basis:

- `DIRECT_MEASUREMENT`
- `CALCULATED`
- `SCHEDULE`
- `INSPECTION`
- `INFERRED`
- `UNKNOWN`

Every system begins as `UNKNOWN / UNKNOWN`. A non-unknown state requires at least one immutable
evidence reference. A calculated state additionally requires a versioned equation ID and version.
Changing the vehicle profile appends a fresh unknown baseline while retaining every earlier
assessment; it never silently carries a conclusion across a changed configuration.

## SQLite migration and export

Database schema v2 adds, without deleting v1 evidence:

- `head_unit_inventory`
- `vehicle_profiles`
- `health_assessments`

Profiles and assessments form explicit `supersedes` chains. The app exports the current complete
view as `vehicle.digital-twin.snapshot@1.0.0` JSON through Android's Storage Access Framework.
Existing `.vhossync` raw evidence remains a separate checksummed artifact.

## Honest implementation status

Implemented now:

- automatic inventory capture and refresh;
- manual 4Runner configuration entry with permanent revisions;
- V6/V8 timing-drive guard;
- complete unknown-by-default health map;
- non-destructive database v1-to-v2 migration; and
- versioned digital-twin JSON export.

Not yet implemented and therefore not presented as complete:

- encrypted SQLite pages (the current database is app-sandboxed SQLite/WAL; the UI marks
  encryption at rest as not enabled);
- official Toyota maintenance rules and capacity/part data;
- service, inspection, receipt, photograph, warranty, and component-life records;
- promotion of validated CAN/J1979 signals into health assessments;
- versioned calculation runs and forecasts; and
- two-way iOS merge/restore for digital-twin records.

The next database migration should add Keystore-wrapped database encryption before service history
or owner documents are stored.
