# Maintenance Garage UI

## Purpose

The Maintenance Garage is the head unit's vehicle-agnostic maintenance record workspace. It stores
what an owner, technician, import, or system actually recorded. It does not infer that a component
is healthy because no record, diagnostic trouble code, or overdue schedule is present.

Android owns the canonical encrypted local ledger. Future iPhone and backup clients exchange
versioned mutations and accepted receipts with Android; they do not silently maintain a competing
maintenance database.

## Landscape workflow

The persistent top controls provide:

1. A current vehicle selector plus **New vehicle** and append-only **Edit vehicle**.
2. Text and event-type filters plus an explicit **Voided** toggle.
3. **New component** and **Components** for installed/removed/retired physical-part identities and
   their immutable lifecycle history.
4. **New record** for service, repair, inspection, replacement, fluid service, installation,
   removal, adjustment, diagnostic work, and owner notes.
5. A left maintenance timeline, center record detail, and right immutable revision/audit history.
6. **Amend** and **Void** actions only while the current record is active.
7. **Attach receipt / photo** on an active record, using Android's system document picker.
8. **Plan & configuration** for generic configuration evidence, severe-use conditions, maintenance
   requirements, and due-state explanations.
9. **Export archive** for an owner-controlled, checksummed `.vhosmaintenance` copy of one vehicle.

No sample vehicles or maintenance rows are inserted. A new installation starts with an honest empty
state and asks the owner to create a vehicle asset.

## Structured entry

Vehicle assets retain display name, year, make, model, trim, VIN, plate, distance unit, and current
odometer. Their versioned configuration snapshot can retain generic make/model-independent keys,
explicit `KNOWN`/`UNKNOWN`/`NOT_APPLICABLE` state, provenance, and tri-state severe-use evidence.
Physical-component revisions retain stable component identity, system, lifecycle state, install or
retirement evidence, notes, and typed custom fields. Maintenance entries retain:

- event type, title, occurrence time, odometer, and engine hours;
- canonical system and component identity;
- provider contact/invoice data and ISO-4217 money;
- repeatable part, fluid, supply, labor, and other line items;
- repeatable decimal, text, and Boolean measurements with units/method/condition grade;
- warranty dates, distance limit, and terms;
- notes and repeatable typed custom fields (text, decimal, integer, Boolean, date, instant, choice);
- completion claims that explicitly identify the maintenance task/rule/baseline evidence; and
- any attachment metadata already bound to the revision.

Custom fields are structured typed values, not an unvalidated JSON escape hatch. Domain validation
rejects malformed canonical keys, units on nonnumeric values, invalid dates/instants, and ambiguous
Boolean encodings.

## Mutation semantics

- Creating a vehicle or maintenance record appends the first revision.
- Editing a vehicle appends a new revision bound to the previous vehicle revision.
- Revising configuration or severe-use evidence appends a new vehicle revision and invalidates a
  prior verified-pack match until the current configuration is re-evaluated.
- Installing, revising, re-parenting, or retiring a component appends a component revision while
  preserving its stable physical identity.
- Amending a maintenance record appends a new active revision and a reason.
- Voiding appends a voided revision and audit event with a required reason.
- Creating, amending/re-evaluating, or voiding an owner-custom maintenance rule appends a
  maintenance-requirement revision; verified-pack rules remain read-only.
- A voided record remains visible when the **Voided** filter is enabled and cannot be amended.
- Current maintenance writes bind to the current vehicle-asset revision so identity changes remain
  reproducible.

Deletion is intentionally absent from the UI. The history and audit panes make correction lineage
visible instead of rewriting the past. SQL triggers also reject direct `UPDATE` and `DELETE` against
the eight maintenance source tables, so the append-only rule is enforced below the UI/API layer.

## Plan, applicability, and source authority

The **Maintenance Plan & Applicability** workspace keeps vehicle configuration and severe-use
evidence next to current maintenance requirements. Owner-created rules may define distance, time,
or engine-hour intervals and warning windows. Every projection retains the exact vehicle revision,
rule revision, applicability result, current counters, and qualifying completion/baseline evidence.
Missing facts do not default to a favorable result: due state remains `UNKNOWN` until the required
inputs are present.

Verified vehicle-pack rules are read-only and require trusted ingestion plus an exact match to the
current vehicle-asset revision. The staged Toyota 2005 scheduled-maintenance source manifest is only
a draft research/source receipt. It remains inactive until all chart and footnote applicability is
normalized, independently reviewed, signed/activated, and matched to a resolved vehicle revision.
No Toyota requirement is silently assigned to a generic vehicle or made authoritative by the draft.

## Receipts, photographs, and portable archives

The document picker reads a selected receipt, photograph, PDF, or other document through a bounded
64 MB stream. Its body is stored once, by SHA-256, in the `maintenance_attachment_blobs.body` BLOB
column inside the same SQLCipher database as the ledger. There is no plaintext attachment-object
directory in app storage.
The record receives only validated metadata and a content address. Attaching evidence appends a new
record revision, preserves every prior field and maintenance-completion claim, and produces a UI
receipt with the exact byte count, media type, SHA-256, and revision ID. A stale revision cannot be
silently amended if the record changed while the picker was open.

Portable export uses Android's system create-document flow. The deterministic archive contains the
complete vehicle, component, maintenance-requirement, record-revision, and audit histories plus all
available referenced attachment bodies. The app displays the whole-archive and manifest SHA-256
receipts after a successful write. Archive export does not mutate the ledger, and archive import is
not exposed until transactional restore is implemented and verified. Inside the portable ZIP only,
attachment entries use content-addressed paths; those archive paths do not describe Android's live
storage layout.

## iPhone companion boundary

The eventual iPhone Garage should consume the same vehicle and maintenance contracts, display the
Android-accepted current revision, and show pending/submitted/accepted/rejected sync state. Android
remains authoritative for local vehicle truth. Portable exports must preserve record IDs, revision
IDs, predecessor links, actor identity, timestamps, attachment hashes, and audit receipts.

## Acceptance behavior

- The screen remains usable with zero vehicles and zero records.
- Switching vehicles cannot show another vehicle's records.
- Search and event filters query only current revisions unless **Voided** is selected.
- Every displayed amount and distance includes its stored currency/distance unit.
- Invalid entry remains in the dialog with a concrete validation message.
- Database work stays off the UI thread and stale responses cannot overwrite a newer selection.
- Empty history is rendered as unknown/unrecorded, never as a positive health claim.

## Remaining acceptance work

Automated domain, persistence, migration, archive-codec, and UI-helper tests cover the implemented
paths. A physical Android head-unit run is still required to validate the device's actual Storage
Access Framework picker/provider behavior and the resulting owner-selected archive destination.
Transactional `.vhosmaintenance` import/restore is deliberately absent from the Garage UI until its
all-or-nothing database acceptance path and physical-device recovery procedure are complete.
