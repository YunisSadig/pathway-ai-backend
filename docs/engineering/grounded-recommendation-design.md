# Grounded recommendation engine: design (no model training, nothing deployed)

Status: design only (2026-11-19). It consumes the verified PostgreSQL catalogue; it never replaces it.

## Principle
The database decides *what is true and what the applicant is eligible for*; a language model (existing hosted model or an open model such as Qwen behind an interface) only *explains* those results in the applicant's language. Training or fine-tuning a model on admissions facts is out of scope: facts change yearly, a weight cannot cite a source, and fine-tuning is only for tone and refusal behaviour (see the 2026-11-17 discussion).

## Components
1. **Candidate retrieval (SQL, deterministic).** Filters on country, level, field, language, budget (tuition only where a dated or explicitly undated fee exists), intake and deadline state (CURRENT / HISTORICAL). No similarity score is shown to the applicant; ordering is explainable (field match, fee within budget, deadline open).
2. **Eligibility checks (rules, deterministic).** Compare the student profile with stored `requirement` rows and university facts: prior qualification level, language test (test + minimum score), required documents. Output per programme: `MEETS`, `DOES_NOT_MEET`, `UNKNOWN` with the source of each rule. No admission probability, ever (product rule).
3. **Explanation layer (LLM, constrained).** Input: the structured result rows only (fields, values, source URL, source name, check date, temporal status, academic year or "year not stated"). Output: a short explanation where every sentence maps to a row id. A response is rejected and regenerated if it contains a number, date, score or requirement that is not in the input rows.
4. **Citation format.** `{fact, value, applicability: "academic year 2027/2028" | "year not stated" | "intake autumn 2027", source: {name,url}, verifiedOn, status: VERIFIED | PENDING_REVIEW | STALE}`. The UI renders the same chips as the programme page.
5. **Uncertainty rules.** Missing field -> "unknown" plus the official link, never an estimate. `HISTORICAL` or `STALE` facts are labelled and not used for ranking. Conflicting sources -> show both and say which is newer.
6. **Privacy.** Send the model only the minimum profile fields needed (level, field, country, language scores, budget); no name, email or free text; no prompt logging with profile data; per-user rate limit and monthly cost cap (no paid service without approval).

## Interface (Java, existing architecture)
`assistant` package behind a feature flag: `CandidateSearch` (SQL), `EligibilityEvaluator` (rules), `ExplanationModel` (interface; one adapter per provider), `GroundingValidator` (checks the answer against the input rows). Nothing outside the flag changes.

## Evaluation (before any deployment)
- 150-200 synthetic profile/question cases from `SyntheticFixtures`, each with expected eligibility result and expected citations.
- Trap cases: missing fee, past deadline, undated fee, conflicting sources, request for "my chances", request to invent a requirement.
- Metrics: citation coverage (every claim sourced), fabricated-fact rate (must be 0), "unknown" correctness, refusal correctness, language quality (EN/RU).
- Run on every prompt/model change; model choice by measured results, not by name.

## Not now
Model training; vector search over unverified text; any answer that implies an admission probability; production deployment.
