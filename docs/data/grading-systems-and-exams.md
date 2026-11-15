# Grading systems and standardized exams: research record

Verification date: **2026-11-15**. Purpose: decide which grade and exam inputs Pathway AI may offer, and what it must NOT do.

## Decision rules that follow from the research

1. **Pathway AI never converts grades.** No conversion to a US GPA, no cross-country equivalence, no admission estimate. A grade is stored
   exactly as the student reads it from the transcript, together with the scale it is on.
2. **A student chooses the scale that is printed on their own document.** Country alone never selects it. A Kazakhstani student is not
   offered an Azerbaijani scale because of their citizenship, and vice versa.
3. Only **structurally defined** scales are offered as ready-made choices (a number out of a stated maximum, or letters). Anything else uses
   "Another scale", where the student states a label, the lowest and the highest value of that scale. Nothing is guessed.
4. Even grade POINTS differ between institutions (see Georgia below), so letter-to-point tables are never built in.

## Evidence level used in the tables

- **PRIMARY**: the examination body or the institution's own published rules (read in full).
- **INSTITUTIONAL**: a university's published rules; proves that the scale exists at that university, not that every university uses it.
- **SECONDARY**: a converter website or encyclopedia; used only as a pointer, never as a basis for a default.
- **NOT FOUND**: the ministry's regulation text could not be retrieved in this research.

## Country findings

| Country | School marks | University grades | Varies by institution? | Evidence |
| --- | --- | --- | --- | --- |
| Azerbaijan | 5-point classroom marks; school-leaving exam components on a 100-point scale | Most universities report 0-100 with letters A-F (example: Western Caspian University: F below 51, E 51-60, D 61-70, C 71-80, B 81-90, A 91-100); credit system under ministry-approved rules (ECTS-referenced); some institutions use other scales | **Yes** | INSTITUTIONAL (wcu.edu.az, unec.edu.az); ministry text NOT FOUND; converter sites disagree on cut-offs (SECONDARY) |
| Kazakhstan | 5-point marks | Letter / point-rating system with GPA, commonly 0-100 with letters and GPA out of 4.0 (example: Nazarbayev University reports a cumulative GPA out of 4.00); national framework cites Government Resolution 597 (27.11.2017) and Ministry Order 595 (30.10.2018) | **Yes** (universities set their rules within the framework) | INSTITUTIONAL (registrar.nu.edu.kz, satbayev.university, farabi.university); ministry text NOT FOUND |
| Georgia | 10-point system (national curriculum); schools may set their own, converted to 10-point | 100-point with letters A-F (A 91-100, B 81-90, C 71-80, D 61-70, E 51-60, F/FX 0-50); GPA out of 4.0 is an **institution rule**: at Tbilisi State University the 100-point bands give 4.0/3.0/2.0/1.0/0.5/0.0, while its 5-point marks give 4.0/2.5/1.0/0/0 | **Yes** | PRIMARY for TSU (GPA Rules, read in full); Eurydice (EU) for the 100-point system and the 10-point school system |
| Uzbekistan | 5-point marks | 5-point scale converted to a 100-point scale; passing is 3.00 / 60 (National University of Uzbekistan); the 2018 ministry regulation on knowledge control governs; credit system being introduced; converter sites differ on letter mapping | **Yes** | INSTITUTIONAL (nuu.uz, read in full, cites lex.uz/docs/391679); ministry text NOT FOUND |
| Kyrgyzstan | 5-point marks; a 24.kg report says a move to a 10-point scale accompanies the 12-year school plan (**not confirmed as implemented**) | 100-point rating (BRS) at universities (KGMA, IUK); 5-point also referenced | **Yes** | INSTITUTIONAL (kgma.kg, iuk.kg); ministry text NOT FOUND; 10-point change UNVERIFIED |

## What Pathway AI offers (all five countries share the same structural choices)

| Scale code | Student sees | Stored as | Range check | Offered for |
| --- | --- | --- | --- | --- |
| `FIVE_POINT` | "Marks 1-5 (5 is the highest)" | decimal 1-5 (two decimals) | 1 to 5 | school and university: attested in AZ, KZ, UZ, KG sources |
| `TEN_POINT` | "Marks 1-10 (10 is the highest)" | decimal 1-10 | 1 to 10 | school: attested for Georgia (Eurydice); offered everywhere because a school may use it, never preselected by country |
| `PERCENT_100` | "Out of 100" | decimal 0-100 | 0 to 100 | university: attested in all five |
| `GPA_4` | "GPA out of 4.0" | decimal 0-4 (two decimals) | 0 to 4 | university: attested (KZ: NU; GE: TSU). **Not a conversion**: the student enters the GPA their university printed |
| `LETTER` | "Letter grades" | letter A-F with optional +/-, or the ECTS letters | fixed list | university: attested in all five |
| `OTHER` | "Another scale (as on my transcript)" | value, label, lowest, highest | min < max, value within | any: the student states the scale |

**Not offered**: "GPA out of 5.0". No official source for a 5.0 GPA scale in these five countries was found (SECONDARY sources only), so it
is available only through `OTHER`, where the student states the maximum.
**Defaults by country**: none. The wizard shows the choices in a sensible order for the education level but never pre-selects a scale from the
country, and shows the hint "Choose the scale that is printed on your transcript".

## Existing data (V9 profile)

V9 stored `gpa` and `gpa_scale_max` (a free number). These rows are **left untouched**. The new model stores grades in separate columns, the
old values are shown to the student as "entered earlier: 3.4 out of 4.0" and are never copied or reinterpreted. The student re-enters the
grade on a named scale when they next edit the profile.

## Exams (official rules read from the examination bodies)

| Exam | Scale | Sections | Source | Notes |
| --- | --- | --- | --- | --- |
| IELTS | bands 1-9 in half bands (0 = did not attempt) | Listening, Reading, Writing, Speaking; overall = average rounded to the nearest half band | **PRIMARY** ielts.org "IELTS scoring in detail" | IELTS **recommends** treating results as valid for two years; organisations may accept longer. Academic and General Training are different test types and are recorded separately. |
| TOEFL iBT, new report | 1-6 in 0.5 steps; sections Reading, Listening, Writing, Speaking; overall = average rounded to 0.5; effective **21 January 2026** | four sections | **PRIMARY** ETS "TOEFL iBT score scale update" (toeflaccess.ets.org, article 11758616) | Reports carry the CEFR level and, for two years, a comparable 0-120 total. ETS gives an official concordance table (6 = 114+, 5.5 = 107+, 5 = 95+, 4.5 = 86+, 4 = 72+, 3.5 = 58+, 3 = 44+, 2.5 = 34+, 2 = 24+, 1.5 = 12+, 1 = 0+). Pathway AI **stores the report format it was given and never converts**. |
| TOEFL iBT, legacy report | sections 0-30, total 0-120 (sum of sections) | four sections | **PRIMARY** same ETS article | Tests taken before 21 Jan 2026 stay legacy reports. |
| SAT | total 400-1600; Reading and Writing 200-800; Math 200-800 | two sections | **PRIMARY** College Board (satsuite.collegeboard.org "What scores mean") | The Essay (1-4) is not part of this release. The College Board page does not state an expiry. |

**Unverified and therefore not shown**: an official validity period for TOEFL and SAT scores. The interface shows the test date, never an
"expired" label for these. For IELTS it shows the neutral note "IELTS recommends results from the last two years" when the test date is older
than two years.
**Never done**: equivalences between IELTS, TOEFL and SAT, or university acceptance rules.

## Sources

- IELTS: https://ielts.org/take-a-test/your-results/ielts-scoring-in-detail (read 2026-11-15)
- ETS: https://toeflaccess.ets.org/help/en/articles/11758616-toefl-ibt-score-scale-update-what-you-need-to-know (read 2026-11-15)
- College Board: https://satsuite.collegeboard.org/scores/what-scores-mean (read 2026-11-15)
- Tbilisi State University GPA rules: https://tsu.ge/assets/media/files/42/Inbound%20Mobilities/GPA_Rules%20(English%20version).pdf (read in full 2026-11-15)
- Eurydice, Georgia: https://eurydice.eacea.ec.europa.eu/eurypedia/georgia/bachelor
- National University of Uzbekistan: https://nuu.uz/en/baholash-tizimi/ (read 2026-11-15; cites https://lex.uz/docs/391679)
- Western Caspian University (Azerbaijan): https://wcu.edu.az/en/page/qiymetlendirme ; UNEC credit system: https://unec.edu.az/en/?p=456632
- Nazarbayev University registrar (Kazakhstan): https://registrar.nu.edu.kz/sites/default/files/GR%20Orientation%20presentation_Office%20of%20the%20Registrar.pdf
- Kazakhstan framework references: https://official.satbayev.university (DP KazNRTU 706), https://farabi.university academic policy 2025-2026
- Kyrgyzstan: https://kgma.kg/index.php/en/international-mobility/grading-system ; https://muk.iuk.kg/en/for-students/memo-to-the-student/ ; https://24.kg/english/302452_Switch_to_12-year_school_education_Ten-point_grading_scale_to_be_used

## Open items

- The five ministries' own regulations were not retrieved; a future pass should add them and upgrade the evidence level.
- Kyrgyzstan's possible 10-point school scale: confirm with the ministry before adding anything beyond `TEN_POINT` (already structurally offered).
- New countries are enabled by configuration only (`docs/product/` and the country configuration in the backend); no onboarding rewrite.
