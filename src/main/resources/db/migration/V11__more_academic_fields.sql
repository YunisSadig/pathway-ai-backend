-- V11: more academic fields for the official classification mapping (reference-data/studyinfo-field-map.json).
-- Additive and idempotent: classification labels only, no academic records. A program gets a field only from an official code.
INSERT INTO academic_field (slug, name) VALUES
    ('education', 'Education'),
    ('arts-and-media', 'Arts and Media'),
    ('business-and-administration', 'Business and Administration'),
    ('law', 'Law'),
    ('health-and-welfare', 'Health and Welfare'),
    ('agriculture-forestry-and-veterinary', 'Agriculture, Forestry, Fisheries and Veterinary'),
    ('services', 'Services')
ON CONFLICT (slug) DO NOTHING;
