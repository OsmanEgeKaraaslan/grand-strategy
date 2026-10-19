-- Phase 3 seed: "The Sundered Reach", version 1 — a small fictional two-nation map.
-- Province polygons come from a deterministic jittered lattice: neighbouring provinces share
-- the exact same border points, so the drawn map and the adjacency graph always agree.
-- Never edit this migration; publish changes as a new map version.

INSERT INTO map_template (key, version, name, view_box, max_players)
VALUES ('sundered-reach', 1, 'The Sundered Reach', '0 0 1000 640', 2);

INSERT INTO province_template (map_template_id, key, name, terrain, svg_path, label_x, label_y, city_name, start_population)
SELECT m.id, v.key, v.name, v.terrain, v.svg_path, v.label_x, v.label_y, v.city_name, v.start_population
FROM map_template m
CROSS JOIN (VALUES
('harrowmere', 'Harrowmere', 'PLAINS', 'M52 23 L107 12 L162 2 L216 12 L271 23 L262 84 L281 141 L297 199 L299 259 L241 234 L181 223 L118 230 L59 215 L58 167 L61 119 L67 70 Z', 170, 130, 'Harrow', 6000),
('greyspire', 'Greyspire Heights', 'MOUNTAINS', 'M271 23 L328 32 L386 42 L444 41 L502 32 L491 83 L508 133 L502 184 L520 233 L463 225 L411 261 L355 262 L299 259 L297 199 L281 141 L262 84 Z', 398, 137, 'Spirewatch', 4000),
('kaldric', 'Kaldric Peaks', 'MOUNTAINS', 'M502 32 L562 25 L622 50 L682 32 L742 31 L721 83 L709 137 L712 195 L685 245 L643 247 L603 229 L562 224 L520 233 L502 184 L508 133 L491 83 Z', 612, 135, 'Kaldrin', 4000),
('ostmark', 'Ostmark', 'PLAINS', 'M742 31 L791 20 L838 35 L885 41 L933 52 L934 100 L948 147 L942 195 L934 243 L872 253 L810 246 L747 253 L685 245 L712 195 L709 137 L721 83 Z', 823, 143, 'Ostmarch', 6000),
('varnhold', 'Varnhold', 'PLAINS', 'M59 215 L118 230 L181 223 L241 234 L299 259 L284 296 L282 337 L254 370 L250 411 L205 422 L163 407 L121 382 L76 393 L64 349 L75 303 L68 259 Z', 171, 319, 'Varn', 12000),
('elmsford', 'Elmsford', 'PLAINS', 'M299 259 L355 262 L411 261 L463 225 L520 233 L517 279 L491 318 L468 357 L467 403 L412 391 L359 414 L304 394 L250 411 L254 370 L282 337 L284 296 Z', 384, 327, 'Elmsford', 8000),
('sablewater', 'Sablewater', 'PLAINS', 'M520 233 L562 224 L603 229 L643 247 L685 245 L707 283 L723 323 L731 366 L748 406 L677 403 L607 412 L537 400 L467 403 L468 357 L491 318 L517 279 Z', 605, 322, 'Sable', 8000),
('ostrava', 'Ostrava Vale', 'PLAINS', 'M685 245 L747 253 L810 246 L872 253 L934 243 L941 285 L914 325 L929 367 L925 408 L881 422 L836 417 L792 410 L748 406 L731 366 L723 323 L707 283 Z', 823, 325, 'Ostra', 12000),
('brindle', 'Brindle Coast', 'PLAINS', 'M76 393 L121 382 L163 407 L205 422 L250 411 L253 457 L269 501 L268 548 L276 594 L220 623 L162 614 L104 605 L46 607 L75 556 L58 499 L56 445 Z', 162, 501, 'Brindleport', 7000),
('fenwick', 'Fenwick', 'PLAINS', 'M250 411 L304 394 L359 414 L412 391 L467 403 L471 456 L491 504 L509 553 L520 603 L459 614 L399 589 L338 576 L276 594 L268 548 L269 501 L253 457 Z', 378, 503, 'Fenwick', 5000),
('marrowfield', 'Marrowfield', 'PLAINS', 'M467 403 L537 400 L607 412 L677 403 L748 406 L759 450 L747 494 L754 539 L738 582 L683 580 L631 608 L577 615 L520 603 L509 553 L491 504 L471 456 Z', 618, 498, 'Marrow', 5000),
('saltreach', 'Saltreach', 'PLAINS', 'M748 406 L792 410 L836 417 L881 422 L925 408 L912 456 L927 499 L966 538 L955 586 L901 571 L847 574 L793 563 L738 582 L754 539 L747 494 L759 450 Z', 842, 495, 'Saltholm', 7000)
) AS v (key, name, terrain, svg_path, label_x, label_y, city_name, start_population)
WHERE m.key = 'sundered-reach' AND m.version = 1;

-- Rivers run along the Elmsford/Sablewater and Fenwick/Marrowfield borders. No river edge
-- touches a mountain province, so river and mountain penalties never combine on this map.
INSERT INTO province_adjacency (map_template_id, province_a_id, province_b_id, crossing, river_svg_path)
SELECT m.id, LEAST(a.id, b.id), GREATEST(a.id, b.id), v.crossing, v.river_svg_path
FROM (VALUES
('harrowmere', 'greyspire', 'NONE', NULL),
('harrowmere', 'varnhold', 'NONE', NULL),
('greyspire', 'kaldric', 'NONE', NULL),
('greyspire', 'elmsford', 'NONE', NULL),
('kaldric', 'ostmark', 'NONE', NULL),
('kaldric', 'sablewater', 'NONE', NULL),
('ostmark', 'ostrava', 'NONE', NULL),
('varnhold', 'elmsford', 'NONE', NULL),
('varnhold', 'brindle', 'NONE', NULL),
('elmsford', 'sablewater', 'RIVER', 'M520 233 L517 279 L491 318 L468 357 L467 403'),
('elmsford', 'fenwick', 'NONE', NULL),
('sablewater', 'ostrava', 'NONE', NULL),
('sablewater', 'marrowfield', 'NONE', NULL),
('ostrava', 'saltreach', 'NONE', NULL),
('brindle', 'fenwick', 'NONE', NULL),
('fenwick', 'marrowfield', 'RIVER', 'M467 403 L471 456 L491 504 L509 553 L520 603'),
('marrowfield', 'saltreach', 'NONE', NULL)
) AS v (a_key, b_key, crossing, river_svg_path)
JOIN map_template m ON m.key = 'sundered-reach' AND m.version = 1
JOIN province_template a ON a.map_template_id = m.id AND a.key = v.a_key
JOIN province_template b ON b.map_template_id = m.id AND b.key = v.b_key;

INSERT INTO nation_template (map_template_id, key, name, color, capital_province_id)
SELECT m.id, v.key, v.name, v.color, p.id
FROM (VALUES
    ('varn', 'Kingdom of Varn', '#3f6fb5', 'varnhold'),
    ('ostra', 'Ostran Confederacy', '#b5503a', 'ostrava')
) AS v (key, name, color, capital_key)
JOIN map_template m ON m.key = 'sundered-reach' AND m.version = 1
JOIN province_template p ON p.map_template_id = m.id AND p.key = v.capital_key;

INSERT INTO nation_start_province (map_template_id, nation_template_id, province_template_id)
SELECT m.id, n.id, p.id
FROM (VALUES
    ('varn', 'harrowmere'), ('varn', 'greyspire'), ('varn', 'varnhold'),
    ('varn', 'elmsford'), ('varn', 'brindle'), ('varn', 'fenwick'),
    ('ostra', 'kaldric'), ('ostra', 'ostmark'), ('ostra', 'sablewater'),
    ('ostra', 'ostrava'), ('ostra', 'marrowfield'), ('ostra', 'saltreach')
) AS v (nation_key, province_key)
JOIN map_template m ON m.key = 'sundered-reach' AND m.version = 1
JOIN nation_template n ON n.map_template_id = m.id AND n.key = v.nation_key
JOIN province_template p ON p.map_template_id = m.id AND p.key = v.province_key;
