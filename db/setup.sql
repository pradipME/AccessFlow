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
-- It does NOT create any tables. Tables arrive in Phase 1B.
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
-- 3. Grant only what the application needs
-- ------------------------------------------------------------
-- This is the least-privilege principle: the application can do DML
-- (SELECT/INSERT/UPDATE/DELETE) but cannot DROP tables, cannot
-- CREATE databases, and cannot touch any other schema. If the app is
-- ever compromised, the blast radius is one table set.
--
-- No GRANT OPTION -> this user cannot hand out more permissions.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON accessflow.*
    TO 'accessflow'@'localhost';

FLUSH PRIVILEGES;

-- ------------------------------------------------------------
-- 4. Verify
-- ------------------------------------------------------------
SHOW DATABASES LIKE 'accessflow';
SHOW GRANTS FOR 'accessflow'@'localhost';
