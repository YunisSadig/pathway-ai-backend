-- Additive: the staged-change audit accepts IMPLEMENTATION (a curated program moved to a verified successor
-- implementation for a new admission cycle). Same list as V13 plus one value; no row is changed.
ALTER TABLE staged_change DROP CONSTRAINT ck_staged_fact_type;
ALTER TABLE staged_change ADD CONSTRAINT ck_staged_fact_type CHECK (fact_type IN
 ('SOURCE_CONTENT','DURATION_MONTHS','LANGUAGES','TUITION','DEADLINE','REQUIREMENT',
  'PROGRAM_NAME','DEGREE_LEVEL','ACADEMIC_FIELD','CAMPUS_CITY','LIFECYCLE','UNIVERSITY_FACT','IMPLEMENTATION'));
