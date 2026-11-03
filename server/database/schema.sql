-- Tables of the Bioserver database (identical for File #1 and File #2).
-- Derived from upstream database/bioserver{1,2}.sql (Copyright (C) 2013-2019 obsrv.org, AGPL-3.0),
-- without the CREATE USER / CREATE DATABASE lines (scripts/setup.sh does that).
-- Run inside the target database:  mariadb bioserver1 < database/schema.sql

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
SET time_zone = "+00:00";

CREATE TABLE IF NOT EXISTS `sessions` (
  `userid` varchar(14) NOT NULL,
  `ip` varchar(15) NOT NULL,
  `port` int(11) NOT NULL,
  `sessid` varchar(8) NOT NULL,
  `gamesess` bigint(20) NOT NULL DEFAULT '0',
  `lastlogin` timestamp NULL DEFAULT NULL,
  `area` int(11) DEFAULT '-1',
  `room` int(11) DEFAULT '0',
  `slot` int(11) DEFAULT '0',
  `state` int(11) DEFAULT '0',
  KEY `gamesess` (`gamesess`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE IF NOT EXISTS `hnpairs` (
  `userid` varchar(14) NOT NULL,
  `handle` varchar(6) NOT NULL,
  `nickname` varchar(100) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL,
  KEY `userid` (`userid`),
  KEY `handle` (`handle`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE IF NOT EXISTS `motd` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `message` varchar(2000) NOT NULL,
  `active` int(11) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

-- NOTE: passwords are stored in plain text, exactly like upstream. Fine for local
-- testing only; hash them before exposing the server publicly.
CREATE TABLE IF NOT EXISTS `users` (
  `userid` varchar(14) NOT NULL,
  `passwd` varchar(32) NOT NULL,
  PRIMARY KEY (`userid`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
