# A/C temperature-run UI

Status: implemented on `main`; awaiting qualified center-vent hardware telemetry

## Driver-facing workflow

The main head-unit screen contains one full-width **A/C TEMPERATURE RUN** card
with three explicit actions:

1. **Start temperature run** arms the measurement. No timer or temperature is
   fabricated while the sensor is absent.
2. The first qualified center-vent sample establishes the start temperature
   and source-monotonic start time.
3. The live maximum and time to maximum remain **PROVISIONAL** while the run is
   active.
4. **End run** freezes the result. A completed timing result is explicitly not
   an A/C health verdict.
5. **Reset run** clears the runtime projection and returns the card to its empty
   state.

The card shows Celsius and Fahrenheit together, plus start, current, maximum,
temperature rise, accepted sample count, total elapsed time, and the time to
maximum. Missing evidence is displayed with em dashes rather than zeroes.

## Calculation contract

Calculation ID: `AC.TEMP.TIME_TO_MAX.v1`

For one device, capture, and selected temperature channel:

```text
start = first qualified sample after the owner arms the run
maximum = highest qualified value observed through End run
time_to_maximum = source_monotonic_time(first occurrence of maximum)
                  - source_monotonic_time(start)
```

Only `GOOD` observations with a configured calibration at
`BENCH_VALIDATED`, `VEHICLE_VALIDATED`, or `CALIBRATED` status can contribute.
The calculation rejects cross-device or cross-capture joins, sequence
regression, monotonic-time regression, missing values, unverified sensors,
stale observations, decoder uncertainty, transport gaps, and manually entered
values.

An exact duplicate of the newest accepted sample does not change the result.
Equal maximum values retain the first occurrence so the displayed time answers
“when did this run first reach its final maximum?”

## UX interpretation boundary

This calculation answers the requested start-to-maximum question only. During
a center-vent cooling run, the starting sample may be the maximum, producing a
valid `0.0 s` result while the vent temperature subsequently falls. That is not
an error and must not be relabeled as cooling stabilization. Time to minimum
vent temperature and `AC.STABILIZE.v1` are separate future metrics.

The ESP32-S3 internal die-temperature channel is excluded because it is board
health evidence, not cabin or A/C performance evidence.

## Current integration boundary

The released A/C node still runs `EMPTY_RECOVERY` and does not advertise the
VHOS BLE service. The development temperature firmware currently exposes only
uncalibrated board-die evidence over USB serial. Therefore the Android card can
be armed now but cannot complete from the current vehicle hardware, and it will
truthfully remain **WAITING FOR SENSOR**.

When the external center-vent probe, A/C BLE contract, and encrypted evidence
storage path are commissioned, the integration must durably persist the raw
validated telemetry record before passing its qualified projection to
`AcThermalRunReducer`. No transport is allowed to update this UI directly.
