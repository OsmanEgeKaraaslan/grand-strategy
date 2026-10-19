-- Phase 3: static, reusable world templates (docs/DATA_MODEL.md §3.1).
-- Gameplay never writes these tables; a changed map is a new template version, never an edit.
-- Geometry (svg_path, label anchors, river_svg_path) is presentation data that the rules engine ignores.

CREATE TABLE map_template (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    key         varchar(64)  NOT NULL,
    version     integer      NOT NULL,
    name        varchar(80)  NOT NULL,
    view_box    varchar(64)  NOT NULL,
    max_players integer      NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT map_template_key_version_uq UNIQUE (key, version),
    CONSTRAINT map_template_version_ck CHECK (version >= 1),
    CONSTRAINT map_template_max_players_ck CHECK (max_players BETWEEN 2 AND 8)
);

CREATE TABLE province_template (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    map_template_id  uuid         NOT NULL REFERENCES map_template (id),
    key              varchar(64)  NOT NULL,
    name             varchar(80)  NOT NULL,
    terrain          varchar(16)  NOT NULL,
    svg_path         text         NOT NULL,
    label_x          integer      NOT NULL,
    label_y          integer      NOT NULL,
    city_name        varchar(80)  NOT NULL,
    start_population integer      NOT NULL,
    CONSTRAINT province_template_key_uq UNIQUE (map_template_id, key),
    CONSTRAINT province_template_map_id_uq UNIQUE (map_template_id, id),
    CONSTRAINT province_template_terrain_ck CHECK (terrain IN ('PLAINS', 'MOUNTAINS')),
    CONSTRAINT province_template_population_ck CHECK (start_population >= 0),
    CONSTRAINT province_template_path_ck CHECK (svg_path <> '')
);

-- Undirected graph, each edge stored once (a < b). Composite FKs keep both ends on the same map.
CREATE TABLE province_adjacency (
    map_template_id uuid        NOT NULL,
    province_a_id   uuid        NOT NULL,
    province_b_id   uuid        NOT NULL,
    crossing        varchar(16) NOT NULL,
    river_svg_path  text,
    PRIMARY KEY (map_template_id, province_a_id, province_b_id),
    FOREIGN KEY (map_template_id, province_a_id) REFERENCES province_template (map_template_id, id),
    FOREIGN KEY (map_template_id, province_b_id) REFERENCES province_template (map_template_id, id),
    CONSTRAINT province_adjacency_ordered_ck CHECK (province_a_id < province_b_id),
    CONSTRAINT province_adjacency_crossing_ck CHECK (crossing IN ('NONE', 'RIVER')),
    CONSTRAINT province_adjacency_river_path_ck CHECK ((crossing = 'RIVER') = (river_svg_path IS NOT NULL))
);

CREATE INDEX province_adjacency_b_idx ON province_adjacency (map_template_id, province_b_id);

CREATE TABLE nation_template (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    map_template_id     uuid        NOT NULL REFERENCES map_template (id),
    key                 varchar(64) NOT NULL,
    name                varchar(80) NOT NULL,
    color               varchar(7)  NOT NULL,
    capital_province_id uuid        NOT NULL,
    CONSTRAINT nation_template_key_uq UNIQUE (map_template_id, key),
    CONSTRAINT nation_template_map_id_uq UNIQUE (map_template_id, id),
    CONSTRAINT nation_template_color_ck CHECK (color ~ '^#[0-9a-f]{6}$'),
    FOREIGN KEY (map_template_id, capital_province_id) REFERENCES province_template (map_template_id, id)
);

-- Starting legal owner (and controller) of each province. A province starts with at most one
-- nation; the MVP validator additionally requires exactly one (no neutral land, Q-W4).
CREATE TABLE nation_start_province (
    map_template_id       uuid NOT NULL,
    nation_template_id    uuid NOT NULL,
    province_template_id  uuid NOT NULL,
    PRIMARY KEY (nation_template_id, province_template_id),
    CONSTRAINT nation_start_province_uq UNIQUE (map_template_id, province_template_id),
    FOREIGN KEY (map_template_id, nation_template_id) REFERENCES nation_template (map_template_id, id),
    FOREIGN KEY (map_template_id, province_template_id) REFERENCES province_template (map_template_id, id)
);

ALTER TABLE map_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE province_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE province_adjacency ENABLE ROW LEVEL SECURITY;
ALTER TABLE nation_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE nation_start_province ENABLE ROW LEVEL SECURITY;
