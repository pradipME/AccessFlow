-- ==========================================================
-- AccessFlow - database bootstrap
--
-- Run this ONCE, as a MySQL administrative user (e.g. root),
-- from MySQL Workbench or the mysql CLI.
--
-- It creates:
--   1. The database  accessflow
--   2. A dedicated application user  accessflow
--      that may only use that one database.
--
-- It does NOT create any tables. The tables are created by Hibernate from the
-- entities on the first run, because spring.jpa.hibernate.ddl-auto=update
-- (section 4 grants below). For a production schema, do not rely on that:
-- apply the DDL written out in README.md -> "Database schema" instead.
-- ==========================================================

-- ------------------------------------------------------------
-- 1. Create the database
-- ------------------------------------------------------------
-- utf8mb4 is MySQL's full Unicode character set. The older utf8
-- (utf8mb3) cannot store 4-byte characters such as many emoji and
-- some scripts, which causes "incorrect string value" errors later.
-- utf8mb4_general_ci is case-insensitive, so "Alice" and "alice"
-- are treated as the same username.
CREATE DATABASE IF NOT EXISTS accessflow
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 2. Create the dedicated application user
-- ------------------------------------------------------------
-- 'localhost' means the connection originates from this machine.
-- MySQL matches host on the *client* side, so 'accessflow'@'localhost'
-- only ever accepts local connections, never from another host.
--
-- REPLACE THE PASSWORD BELOW with your own strong password before
-- running this script. Do not reuse your MySQL root password.
CREATE USER IF NOT EXISTS 'accessflow'@'localhost'
    IDENTIFIED BY 'REPLACE_WITH_A_STRONG_PASSWORD';

-- ------------------------------------------------------------
-- 3. Runtime grants (least privilege)
-- ------------------------------------------------------------
-- The application can do DML only. It cannot DROP tables, cannot
-- CREATE databases, and cannot touch any other schema. If the app is
-- ever compromised, the blast radius is one table set.
--
-- No GRANT OPTION -> this user cannot hand out more permissions.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON accessflow.*
    TO 'accessflow'@'localhost';

-- ------------------------------------------------------------
-- 4. DEVELOPMENT-ONLY schema grants
-- ------------------------------------------------------------
-- REQUIRED BY spring.jpa.hibernate.ddl-auto=update
--
-- Hibernate creates and alters tables on startup, so the application user
-- needs DDL privileges, not just DML. Without these grants Hibernate fails
-- with "CREATE command denied", logs it at WARN only, and the application
-- starts anyway with NO tables. The failure then surfaces much later as
-- "Table 'accessflow.users' doesn't exist".
--
-- These grants are for LOCAL DEVELOPMENT ONLY. In production the app user
-- should keep the section 3 grants alone and schema changes should be applied
-- by migration tooling or a DBA, never by the running application.
--
-- DROP is deliberately NOT granted. `ddl-auto=update` only ever creates and
-- alters, so nothing here needs it, and leaving it out means a compromised
-- development application cannot drop the tables it can write to. To reset a
-- local schema, drop and re-create the database as root.
--
-- Re-running this block is safe: GRANT is additive and idempotent.
GRANT CREATE, ALTER, INDEX, REFERENCES
    ON accessflow.*
    TO 'accessflow'@'localhost';

FLUSH PRIVILEGES;

-- ------------------------------------------------------------
-- 5. Verify
-- ------------------------------------------------------------
-- The output of the second command must show BOTH sets of grants.
SHOW DATABASES LIKE 'accessflow';
SHOW GRANTS FOR 'accessflow'@'localhost';
