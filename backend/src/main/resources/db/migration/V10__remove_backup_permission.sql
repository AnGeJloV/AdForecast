-- Резервные копии больше не управляются из сайта (оставлен только скрипт db/backup/backup.sh).
DELETE FROM permissions WHERE permission_code = 'backup.manage';
