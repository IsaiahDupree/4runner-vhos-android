# Maintenance data architecture

## Product boundary

The Maintenance Garage is a whole-vehicle record system, not a reminder list. It keeps four
different kinds of truth separate:

1. **Vehicle configuration** — the exact vehicle, resolved and unresolved equipment, canonical
   distance unit, severe-use conditions, and any activated versioned vehicle pack.
2. **Component lifecycle** — stable identities for physical components and append-only revisions
   when a component is installed, renamed, re-parented, or retired.
3. **Observed work and condition** — service, repair, inspection, replacement, measurements,
   parts/fluids, costs, warranties, notes, custom typed fields, and attachments.
4. **Maintenance requirements** — versioned owner-defined or verified-pack rules, their source and
   applicability, the trusted baseline that starts an interval, and a reproducible due projection.

An empty ledger means **history unknown**. It never means healthy, inspected, or up to date.

## Canonical storage

Android is the canonical in-vehicle writer. Structured data is stored in the existing SQLCipher
database, whose random passphrase is wrapped by Android Keystore. The maintenance tables are
append-only source ledgers plus query projections:

| Data | Source table | Mutation rule |
|---|---|---|
| Vehicle identities/configuration | `vehicle_assets` | append revision |
| Physical components | `vehicle_component_revisions` | append revision / retire |
| Work and inspection records | `maintenance_record_revisions` | create, amend, or void by appending |
| Record-to-system/component index | `maintenance_record_systems`, `maintenance_record_components` | materialized with each immutable revision |
| Audit receipts | `maintenance_audit_events` | automatic append in the same transaction |
| Maintenance requirements | `maintenance_requirement_revisions` | append revision / void |
| Attachment bodies | `maintenance_attachment_blobs` (`body` BLOB) | write once by SHA-256 |

The UI reads a current projection by selecting revisions that have not been superseded. Current
views are disposable; the append-only revisions and audit receipts are the truth.

The database filename is app-private `vhos-evidence.db`. SQLCipher encrypts its pages with a random
passphrase wrapped by Android Keystore. Receipt, photograph, PDF, and other attachment bytes are
stored in the same encrypted database; there is no separate plaintext filesystem object directory.
The attachment table's SHA-256 primary key deduplicates identical bodies and a length/hash check is
performed when bytes are read back.

Every persisted identity is a typed ULID. For example, a `veh_...` cannot be accidentally supplied
where a `component_...` or `maintenance_...` identity is required. Records bind to the exact vehicle
revision that was current when the record was accepted.

## CRUD semantics

"Update" and "delete" are intentionally evidence-safe operations:

- **Create** writes the first immutable revision.
- **Read** exposes the current projection and the complete revision/audit history.
- **Update** appends a replacement revision with actor, time, predecessor, and required reason.
- **Delete** appends a `VOIDED`/retired tombstone with a reason. Physical history is not deleted.

Stale writers cannot overwrite a newer revision. Vehicle odometer values cannot decrease, known
readings cannot silently become unknown, and a vehicle's canonical distance unit cannot be changed
in place. Unit conversion is an explicit data operation, never reinterpretation of the same number.

Database triggers reject direct `UPDATE` and `DELETE` statements against `vehicle_assets`,
`vehicle_component_revisions`, `maintenance_record_revisions`, `maintenance_record_systems`,
`maintenance_record_components`, `maintenance_audit_events`,
`maintenance_requirement_revisions`, and `maintenance_attachment_blobs`. This keeps the source
ledger append-only even if a future code path bypasses the public maintenance APIs.

## Generic vehicle configuration

Configuration is typed key/value evidence so the same app can support any make/model/year without
hard-coding one manufacturer into the database. Typical keys include:

- `vehicle.engine.family`, `vehicle.engine.displacement`, `vehicle.fuel.type`;
- `vehicle.transmission.model`, `vehicle.drivetrain.layout`, `vehicle.transfer_case.present`;
- `vehicle.market`, `vehicle.emissions.jurisdiction`, `vehicle.production.date`;
- tire size/load specification, suspension type, axle/differential equipment;
- owner modifications and maintenance-affecting installed equipment.

Each attribute has `KNOWN`, `UNKNOWN`, or `NOT_APPLICABLE` state plus provenance. Severe-use
conditions are independent tri-state facts (yes/no/unknown), never inferred merely because a rule
mentions towing, dust, desert driving, short trips, or salted roads.

## Maintenance rules and due state

A free-text record title cannot satisfy an OEM rule. A completion claim must identify the exact
maintenance task, rule, pack/version, and baseline trust/evidence. A due projection records every
input it used: current distance/date/hours, applicability snapshot, rule version, and qualifying
completion/baseline revision.

Due state is fail-closed:

- `UNKNOWN` — applicability, current input, or trusted baseline is missing;
- `CURRENT` — a qualifying baseline exists and no threshold is near;
- `UPCOMING` — the configured warning window has been entered;
- `DUE` — a distance/time/hour threshold has been reached;
- `OVERDUE` — the threshold has been exceeded;
- `NOT_APPLICABLE` — a resolved applicability predicate excludes the vehicle.

Owner-created custom requirements are editable through append-only CRUD and are clearly labeled
`OWNER_CUSTOM`. Verified vehicle-pack rules are read-only in the generic UI. A draft source manifest
cannot create a due state.

## OEM source packs

Manufacturer schedules are distributed as separately versioned, read-only vehicle packs. A pack
contains normalized rule facts and source locators, not copied manual artwork or prose. Activation
requires:

- an authoritative source document identity and SHA-256;
- a locator for every rule;
- complete configuration and severe-use applicability predicates;
- independent review and activation status;
- an exact match to a vehicle-asset revision.

The staged 2005 Toyota source manifest is maintained under
`maintenance-rule-packs/drafts/toyota.4runner.2005/source-manifest.json` in the companion product
contracts repository. It is intentionally inactive until the printed maintenance charts and all
footnote applicability are completely normalized, independently reviewed, signed/activated, and
matched to an exact current vehicle revision. The official source receipt identifies Toyota's 2005
scheduled-maintenance guide, publication `05ToyAllMS_MS0001`, SHA-256
`f8e3cc84a78dedd3da90e2b78f03dfa2b89600024bd12ec7fa22c05f35a3d616`.

## Garage UX

The landscape Android experience has three related workspaces:

- **History** — searchable service/repair/inspection timeline, record details, and immutable audit;
- **Components** — stable physical component registry and lifecycle history;
- **Maintenance Plan** — current/unknown/upcoming/due requirements with source and baseline
  explanation, plus create/amend/void for owner-custom requirements.

Long writes remain visible until storage succeeds. Validation errors keep the form data on screen.
Receipts and photographs are hash-verified before the metadata revision is appended.

## Portability and recovery

Android platform backup remains disabled because copying an encrypted database without its Keystore
key is not a reliable restore strategy. Owner-controlled maintenance archives are the portable
recovery path. An archive contains:

- all vehicle, component, requirement, record, and audit revisions;
- attachment bodies under content-addressed paths;
- a manifest with contract versions, byte lengths, and SHA-256 for every entry.

The archive reader rejects traversal paths, duplicates, unlisted entries, missing entries, size
violations, and hash mismatches before any database mutation. Import must stage and validate the
entire archive transactionally before accepting new immutable revisions.

The archive codec/reader and deterministic export UI are implemented. The live SQLCipher database
continues to store attachment bodies as BLOBs; `attachments/sha256/...` paths exist only inside the
portable archive. Transactional archive import/restore is not yet exposed in the Garage UI. Physical
head-unit validation of the system document picker and owner-selected export destination also
remains an explicit deployment acceptance step.
