-- J2: two visible sibling "研发部" nodes under 研发一部 / 研发二部 for org disambiguation demos.
-- Subtree grants for 研发中心 include these paths at runtime via org_path.

INSERT INTO sec_org_node (id, org_code, org_name, parent_id, org_path, org_level, status) VALUES
(9,  'RD1-DEV', '研发部', 3, '/1/2/3/9/',  4, 1),
(10, 'RD2-DEV', '研发部', 4, '/1/2/4/10/', 4, 1);

INSERT INTO dim_org (org_key, org_code, org_name, parent_org_key, org_level, org_path, valid_from, valid_to, is_current) VALUES
(9,  'RD1-DEV', '研发部', 3, 4, '/1/2/3/9/',  '2026-01-01', '9999-12-31', 1),
(10, 'RD2-DEV', '研发部', 4, 4, '/1/2/4/10/', '2026-01-01', '9999-12-31', 1);
