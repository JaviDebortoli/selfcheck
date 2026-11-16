# SelfCheck

**Real-time crew health monitoring for long-duration space missions.**
NASA Space Apps Challenge 2026 · Team Catarsis · Challenge: *Create health monitoring software for astronauts on space
missions.*

**Live demo: https://selfcheck.earth**

SelfCheck receives crew measurements in real time, classifies every value with sourced medical rules, and shows each
crew member's state so it can be understood **at a glance**, with an explanation of what each indicator measures and
what to do when it leaves its range.

- 4 crew members, 7 body groups (radiological, cardiovascular and respiratory, hematological and immunological,
  musculoskeletal and bone, metabolic/renal/nutritional, neurological/cognitive/behavioral, digestive and general
  symptoms) plus the shared habitat (CO2, noise, airborne dust).
- Long-duration missions: the same rules apply to any mission (no mission types).
- Four states: **Normal** (green), **Observation** (yellow), **Alert** (red) and **Not evaluable** (grey).
  Missing, expired or poor-quality data is never shown as Normal.
- Two crew screens (quick view and control center) and a simulator control screen, in **Spanish and English**, with
  day and night themes. The control center charts every variable over the whole mission.
- From the quick view a crew member can start a training session (the control center is told that heart and breathing
  rate, temperature, pressure and SpO2 will be altered by exercise) and request medical assistance (a high-priority
  item in the control center's alert log).

## Run it

Requirements: Docker with Compose. Nothing else (the build runs inside Docker).

```bash
docker compose up --build
```

First start takes a few minutes (images and Gradle/npm builds). Then open:

| URL | What |
| --- | --- |
| http://localhost:3000 | Quick view (tablet, 1280×800) |
| http://localhost:3000/#/control | Control center (1920×1080) |
| http://localhost:3000/#/simulator | Simulator control: start, pause, speed, scenarios, reset |

The mission starts on **day 1 at 08:00** in real time. Use the simulator screen to accelerate the mission clock
(up to 1 mission hour per real second) and to trigger scenarios.

Useful endpoints: `GET :8080/api/snapshot`, `GET :8080/api/stream` (SSE), `GET :8080/api/catalog/{es|en}`,
`GET :8080/api/history/{subjectId}?days=14`, `GET :8080/api/history/{subjectId}/{variable}?from&to` (series of one
variable for the chart), `GET :8081/api/simulator/status`, `POST :8081/api/simulator/scenarios/{id}`,
`POST :8081/api/simulator/reset`, `POST :8080/api/crew/{crewMemberId}/training` and
`POST :8080/api/crew/{crewMemberId}/assistance` with `{"active": true|false}` (crew actions from the quick view).

## How it works

```
simulator ──► Kafka ──► engine (Kafka Streams) ──► Kafka ──► api ──► frontend
 (mission      Avro +     rules from knowledge/      states,     TimescaleDB,   React
  clock,       Schema     per variable, group,       summaries,  REST snapshot, (SSE)
  crew model,  Registry   crew member; alerts        alerts      SSE, catalog)
  scenarios)
```

| Module | Responsibility | Technology |
| --- | --- | --- |
| `contracts` | Avro schemas: medical history, measurement, variable state, summary state, alert, crew event, mission control | Avro IDL |
| `knowledge` | Declarative rules (YAML) with source, review flag and Spanish/English texts | YAML + Kotlin loader |
| `simulator` | Mission clock, crew and habitat model, cadences, scenarios, control API | Kotlin, Spring Boot |
| `engine` | Applies the rules per subject, windows in mission time, streaks, expiry, combinations, alerts | Kotlin, Kafka Streams |
| `api` | History in TimescaleDB, snapshot (REST), live events (SSE), text catalog, trends and variable series | Kotlin, Spring Boot |
| `frontend` | Quick view, control center, simulator screen | React, TypeScript, Vite, Apache ECharts |
| `infra` | Topics, Dockerfiles | Docker Compose |

**Mission clock.** SelfCheck runs on mission time, not wall-clock time. The simulator owns the clock and stamps every
message; every rule window (CO2 1-hour mean, noise 16/8-hour means, 3 nights of sleep, creatinine 48 h/7 days, expiry)
is counted in mission time. That lets the demo show in minutes what the rules watch over hours or days. Pausing stops
time without marking data as missing; resetting clears Kafka, the engine state and the database and starts day 1 again.
Every record carries a *mission epoch* header that grows with each new or reset mission, so the engine and the api
drop anything left over from a previous mission whatever order records arrive in.

**Codes, not sentences.** The backend sends reason codes with parameters (`HIGH`, `{value: 38.6, condition: ">= 38.0"}`);
the frontend translates them with the catalog of the chosen language, built from the texts next to each rule.

## Where the data and thresholds come from

- **Data is simulated.** The simulator stands in for the ship's and suit's measurement systems: a model of four healthy
  young adults with a daily routine (sleep, exercise, rest), in-flight drift and natural variation, plus eight scripted
  scenarios (solar particle event, dehydration, sustained poor sleep, high CO2, high noise, fever from infection,
  atrial fibrillation, accelerated strength and bone loss). Daily calorie and fluid targets are computed for each crew
  member with the NASA JSC-67378 formulas.
- **Every cut has a citable source** and lives in `knowledge/src/main/resources/knowledge/rules/`, one file per group,
  with its source. Main sources:
  - NASA-STD-3001 Vol. 1 (career dose 600 mSv [V1 4030], 250 mSv per solar event [V1 4031], bone density 95 %/90 %
    of pre-flight [V1 4027], muscle strength 80 % of pre-flight) and Vol. 2 (CO2 3 mmHg 1-hour mean [V2 6004]).
  - NASA Human Research Program in-flight evidence: where NASA publishes no in-flight reference range, the cut uses the
    mean measured in astronauts during flight (heart rate, blood pressure, body mass, caloric intake, bone resorption
    markers, muscle strength, protein intake and low body temperature). Lunar dust limit 0.3 mg/m³.
  - NASA OCHMO technical briefs (OCHMO-TB-031 exercise, OCHMO-TB-041 sleep, OCHMO-MTB-004 nausea and vomiting) and
    JSC-67378 nutrition requirements.
  - ISS noise flight rule (Limardo et al., Inter-Noise 2021): 60/72 dBA by day, 50/62 dBA by night.
  - WHO 2024 haemoglobin cut-offs; National Sleep Foundation (Hirshkowitz et al., 2015); PSQI (Buysse et al., 1989);
    PHQ-9 (Kroenke et al., 2001); NCCN fatigue scale; Cognition battery (Basner et al., 2015); Bristol stool scale
    (Lewis and Heaton, 1997); KDIGO acute kidney injury guideline; American Heart Association resuscitation guidelines.
  - The team's rules document (WHO normal adult values) for the remaining vital signs and laboratory values.
- Cuts without a source (marked "proposed rule" in the team documents) are implemented as final values and flagged
  `review: true` until a professional reviews them. Cuts with no number yet (dose-rate threshold, how much fluid
  deviation) give no state. Analysis intervals (weekly blood work, monthly bone density) are provisional and noted in
  each rule.
- SelfCheck is a demonstration, **not a medical device**. Thresholds and guidance must be validated by flight surgeons.

## Demo decisions

- **No authentication** (out of scope for the demo).
- **Simulated data only**; no ground link, no manual data entry and no measurement reminders.
- Crew members are anonymous (Crew member A–D).

## Documentation

| Document | Language |
| --- | --- |
| [Functional documentation](2026_SelfCheck_DocumentaciónFuncional.pdf) | Spanish |
| [Technical documentation](2026_SelfCheck_Technical%20Documentation.pdf) / [Documentación técnica](2026_SelfCheck_Documentación%20técnica.pdf) | English / Spanish |
| [User manual](2026_SelfCheck_UserManual.pdf) / [Manual de usuario](2026_SelfCheck_ManualUsuario.pdf) | English / Spanish |

## Tests

```bash
./gradlew test          # 122 tests: rule engine (every NASA and ISS limit), knowledge, simulator, api
cd frontend && npm run build
```

## Use of AI

This project was built during the event with the help of **Claude Code (Anthropic)** as a coding assistant: it wrote
most of the code under the team's direction, following the team's specification, rules document and interface
prototypes, which define every threshold, source and behavior. The team reviewed and tested the results. AI was also
used for research on NASA in-flight evidence, for writing and translating documentation, and for the demo videos: their
slides and editing were produced with AI assistance and carry a visible watermark, and their narration is an
AI-generated voice (Microsoft Edge neural text-to-speech through edge-tts), declared in each video's description and
metadata. The app footage in the videos is real screen capture.

## Open-source software

Kotlin, Spring Boot, Spring for Apache Kafka, Apache Kafka and Kafka Streams, Apache Avro (Apache 2.0); Confluent
Schema Registry and serializers (Confluent Community License); PostgreSQL (PostgreSQL License); TimescaleDB (Timescale
License, used unmodified as a database); Flyway (Apache 2.0); Jackson (Apache 2.0); React (MIT); Vite (MIT);
TypeScript (Apache 2.0); Apache ECharts (Apache 2.0); nginx (BSD-2-Clause); IBM Plex fonts (SIL OFL 1.1). Playwright,
FFmpeg and edge-tts were used only to produce screenshots and the demo videos.

## License

[Apache License 2.0](LICENSE).
