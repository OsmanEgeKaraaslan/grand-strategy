-- Product owner renaming (2026-11-02): the game is now "Clash of Empires" and every faction has a new
-- display name. Only names change: keys, colours, tiers, capitals and every rule stay the same, so the
-- rulesets (which refer to nations by key) and all games are unaffected. Each game keeps a copy of its
-- nations' names from its start; those copies are renamed too, so running games show the new names.
-- The four strongest Grand Campaign nations (tier POWER) become the four empires.

CREATE TEMPORARY TABLE faction_rename (map_key varchar(64), nation_key varchar(64), new_name varchar(80)) ON COMMIT DROP;

INSERT INTO faction_rename (map_key, nation_key, new_name) VALUES
    ('grand-campaign', 'castavar',  'Empire of Kodor'),
    ('grand-campaign', 'nordmark',  'Empire of Kalirferstgrad'),
    ('grand-campaign', 'vostrava',  'Empire of Porvolo'),
    ('grand-campaign', 'dravonia',  'Empire of Trots'),
    ('grand-campaign', 'aurelia',   'Kingdom of Wilkreen'),
    ('grand-campaign', 'serenna',   'Kingdom of Darpholia'),
    ('grand-campaign', 'karpathia', 'Kingdom of Friltenbour'),
    ('grand-campaign', 'frisia',    'Kingdom of Frantisco'),
    ('grand-campaign', 'helvaria',  'Kingdom of Yorklar'),
    ('grand-campaign', 'aestia',    'Kingdom of Krees'),
    ('sundered-reach', 'varn',      'Kingdom of Berlardfaul'),
    ('sundered-reach', 'ostra',     'Kingdom of Antara');

UPDATE nation_template nt
SET name = r.new_name
FROM faction_rename r, map_template m
WHERE m.key = r.map_key AND nt.map_template_id = m.id AND nt.key = r.nation_key;

UPDATE nation n
SET name = nt.name
FROM nation_template nt
JOIN map_template m ON m.id = nt.map_template_id
JOIN faction_rename r ON r.map_key = m.key AND r.nation_key = nt.key
WHERE n.nation_template_id = nt.id;
