# Signal hypothesis research surface

Status: implemented in Android `0.1.0-dev.11`

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
| Pack version | `0.3.0` |
| Contract | `can.signal-hypothesis-pack@1.0.0` |
| SHA-256 | `cd1467986138a11c8554d15252ffe8586ae48fdc8d507acbb846ae2fac204c7b` |
| Product source commit | `767551a` |
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

The complete Android gate is:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew test lint assembleDebug
```

Physical validation still requires a synchronized 2005 4Runner capture with Techstream or approved
read-only J1979 evidence. Until then, all candidate semantics remain unavailable to owner-facing
health views.
