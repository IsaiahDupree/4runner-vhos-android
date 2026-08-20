# Signal hypothesis research surface

Status: implemented in Android `0.1.0-dev.12`

## Purpose

The head unit can now evaluate retained 2005 4Runner CAN bytes against a versioned collection of
cross-model Toyota research without converting those hypotheses into production vehicle signals.
The surface exists to shorten the path from raw evidence to the next controlled validation test.
It does not diagnose the vehicle and cannot update the whole-vehicle digital twin.

Every rendered candidate is labeled:

```text
UNVERIFIED CROSS-MODEL HYPOTHESIS
ENGINEERING_RESEARCH ONLY
```

## Pinned input

Android bundles an exact copy of the product repository's
`toyota-4runner-2005-passive-can-hypotheses.v1.json` at:

```text
core/discovery/src/main/resources/vhos/vehicle-signal-packs/
  toyota-4runner-2005-passive-can-hypotheses.v1.json
```

| Field | Value |
| --- | --- |
| Pack ID | `toyota.4runner.2005.passive-can-hypotheses` |
| Pack version | `0.4.0` |
| Contract | `can.signal-hypothesis-pack@1.0.0` |
| SHA-256 | `6e2df8207e8977d613923a01f4bea7a16baba74a1869cce2ad0a83b56cf6ba32` |
| Product source commit | `60282e6` |
| Authority | `DISCOVERY_ONLY` |
| Accepted signal definitions | `0` |

Startup evaluation fails closed if the resource is missing, its hash changes, its contract is
unsupported, it claims even one accepted definition, enables production display or automatic
promotion, references a missing source or transform, or defines an invalid CAN field.

## Evidence and evaluation boundary

Only observations already committed to the encrypted append-only `can_observations` store are
eligible. Evaluation rejects:

- an empty evidence set;
- a missing source identity;
- any record without listen-only proof; or
- a duplicate source/session/sequence identity.

The evaluator groups exact standard or extended identifiers, extracts only pack-declared raw fields,
and reports count, session count, target-byte activity, range, mean, and standard deviation. It may
also show the numeric result produced by a source-pinned cross-model transform. Those transformed
ranges always say `NOT VERIFIED`; a plausible number is not semantic proof.

No hypothesis evaluation writes a decoded signal, finding, recommendation, maintenance record,
health state, component state, or lifecycle baseline. The existing discovery analysis remains usable
if this separate research-pack evaluation fails.

## Evidence-ranked validation missions

`SignalResearchPlanner` joins the raw discovery report, the hypothesis evaluation, and the exact
SHA-pinned pack lineage. It then ranks what should be validated next. The score is research
priority—not confidence, acceptance, or proof—and the head-unit UI says so beside the ranking.

The planner can assign these stages:

| Stage | Meaning |
| --- | --- |
| `CAPTURE_REQUIRED` | the candidate identifier is absent from retained target evidence |
| `UNMAPPED_RESEARCH` | the identifier is present but no physical semantic is assigned |
| `FIELD_DISCOVERY_REQUIRED` | the ID appears, but field layout is unresolved |
| `CONTROLLED_EXCITATION_REQUIRED` | the field is present but remained static in captured states |
| `REFERENCE_DISAMBIGUATION_REQUIRED` | the target field varies but source-pinned transforms conflict |
| `INDEPENDENT_REFERENCE_READY` | a dynamic target field and one source-pinned transform are ready for simultaneous reference testing |

Ranking inputs are all inspectable: hypothesis status, target record/session activity, source count,
class-B primary-research source count, one-versus-conflicting transform state, Toyota checksum
candidate evidence, and strong raw-word relationships found in the retained corpus. Every mission
also carries its blockers and the pack's exact next validation step. A high score can never enable
owner display.

## Current research candidates

| Target ID | Research candidate | Current authority | First independent validation |
| --- | --- | --- | --- |
| `0x2C4` | engine speed; intake-air temperature | cross-model only | J1979 PID `0x0C`/`0x0F` or Techstream on the same monotonic timeline |
| `0x2C1` | accelerator-pedal position | cross-model only | J1979 PID `0x49` when supported or Techstream at separated pedal levels |
| `0x2D0` | turbine speed; selector code | conflicting cross-model fields | Techstream turbine/input speed plus P/R/N/D markers, shifts, coast, and stop |
| `0x025` | steering-wheel angle | semantic family corroborated; layout/scale conflict | physical and Techstream center/left/right sweep with linear fit |
| `0x224` | brake pressure | field/scale conflict; target field static in retained capture | released/light/medium/firm pressure reference |
| `0x223` | stop-light switch | ID-level candidate | physical lamps plus Techstream switch state during repeated presses |
| `0x022` | steering-related unknown | corroborated ID only | synchronized steering sweep and field ranking |
| `0x023` | unknown | corroborated ID/DLC only | synchronized steering/yaw/acceleration/brake/speed references |
| `0x420` | unknown | unmapped | broader labeled operating states |

The source pack retains the exact primary research, version-pinned code artifacts, community leads,
limitations, conflicting definitions, and required validation for every row. Android displays the
lineage-derived gate instead of hiding disagreement.

Pack `0.4.0` adds the peer-reviewed SAE 2012-01-0999 Camry study as independent primary evidence
for the `0x2C1` accelerator-pedal and `0x2C4` engine-speed message families and their observed update
periods. That raises experiment priority without changing the cross-model authority boundary or
accepting either target-vehicle scale.

## Promotion gate

A candidate may move off this engineering surface only after the product Vehicle Signal Pack process
adds all of the following:

1. exact target VIN/configuration applicability;
2. synchronized independent reference evidence;
3. proven field width, byte order, signedness, scale, offset, unit, cadence, and stale behavior;
4. repeatability across ignition cycles and relevant operating states;
5. a versioned accepted decoder with immutable source lineage; and
6. golden replay tests using retained target-vehicle bytes.

Changing the Android badge or UI color is not promotion.

## Automated verification

`SignalHypothesisEvaluatorTest` proves that Android:

- loads the exact SHA-pinned pack with zero accepted definitions;
- evaluates real retained evidence and keeps transforms unverified;
- rejects a modified pack that claims accepted vehicle authority; and
- rejects any input that lacks listen-only proof.

`SignalResearchPlannerTest` uses the checked-in SHA-pinned 256-record slice from the real
5,176-record corpus to prove that Android:

- creates a deterministic ranked mission for every present candidate;
- keeps owner display blocked for every mission;
- separates dynamic reference-ready fields from static excitation-required fields;
- identifies conflicting-transform disambiguation work;
- carries class-B primary research into the reasons without promoting it to target-vehicle proof;
- retains each candidate's exact validation instruction; and
- rejects an evaluation whose signal-pack SHA does not match the planner's pack.

The complete Android gate is:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew test lint assembleDebug
```

Physical validation still requires a synchronized 2005 4Runner capture with Techstream or approved
read-only J1979 evidence. Until then, all candidate semantics remain unavailable to owner-facing
health views.
