-- Local test data (idempotent). Two users so the gameserver relay can be tested.
INSERT IGNORE INTO `users` (`userid`, `passwd`) VALUES ('test1', 'test1'), ('test2', 'test2');
INSERT INTO `motd` (`id`, `message`, `active`) VALUES
  (1, '<BODY><SIZE=3>reo-web local test server<END>', 1)
  ON DUPLICATE KEY UPDATE `message` = VALUES(`message`), `active` = 1;
