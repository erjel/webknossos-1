DROP SCHEMA IF EXISTS webknossos CASCADE;
CREATE SCHEMA webknossos;

CREATE TYPE webknossos.VECTOR3 AS (
  x DOUBLE PRECISION,
  y DOUBLE PRECISION,
  z DOUBLE PRECISION
);
CREATE TYPE webknossos.BOUNDING_BOX AS (
  x DOUBLE PRECISION,
  y DOUBLE PRECISION,
  z DOUBLE PRECISION,
  width DOUBLE PRECISION,
  height DOUBLE PRECISION,
  depth DOUBLE PRECISION
);

START TRANSACTION;
CREATE TABLE webknossos.releaseInformation (
  schemaVersion BIGINT NOT NULL
);
INSERT INTO webknossos.releaseInformation(schemaVersion) values(90);
COMMIT TRANSACTION;


CREATE TYPE webknossos.ANNOTATION_TYPE AS ENUM ('Task', 'Explorational', 'TracingBase', 'Orphan');
CREATE TYPE webknossos.ANNOTATION_STATE AS ENUM ('Active', 'Finished', 'Cancelled', 'Initializing');
CREATE TYPE webknossos.ANNOTATION_VISIBILITY AS ENUM ('Private', 'Internal', 'Public');
CREATE TABLE webknossos.annotations(
  _id CHAR(24) PRIMARY KEY,
  _dataSet CHAR(24) NOT NULL,
  _task CHAR(24),
  _team CHAR(24) NOT NULL,
  _user CHAR(24) NOT NULL,
  _publication CHAR(24),
  description TEXT NOT NULL DEFAULT '',
  visibility webknossos.ANNOTATION_VISIBILITY NOT NULL DEFAULT 'Internal',
  name VARCHAR(256) NOT NULL DEFAULT '',
  viewConfiguration JSONB,
  state webknossos.ANNOTATION_STATE NOT NULL DEFAULT 'Active',
  statistics JSONB NOT NULL,
  tags VARCHAR(256)[] NOT NULL DEFAULT '{}',
  tracingTime BIGINT,
  typ webknossos.ANNOTATION_TYPE NOT NULL,
  othersMayEdit BOOLEAN NOT NULL DEFAULT false,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  CHECK ((typ IN ('TracingBase', 'Task')) = (_task IS NOT NULL)),
  CONSTRAINT statisticsIsJsonObject CHECK(jsonb_typeof(statistics) = 'object')
);


CREATE TYPE webknossos.ANNOTATION_LAYER_TYPE AS ENUM ('Skeleton', 'Volume');
CREATE TABLE webknossos.annotation_layers(
  _annotation CHAR(24) NOT NULL,
  tracingId CHAR(36) NOT NULL UNIQUE,
  typ webknossos.ANNOTATION_LAYER_TYPE NOT NULL,
  name VARCHAR(256) NOT NULL CHECK (name ~* '^[A-Za-z0-9\-_\.]+$'),
  UNIQUE (name, _annotation),
  PRIMARY KEY (_annotation, tracingId)
);

CREATE TABLE webknossos.annotation_sharedTeams(
  _annotation CHAR(24) NOT NULL,
  _team CHAR(24) NOT NULL,
  PRIMARY KEY (_annotation, _team)
);

CREATE TABLE webknossos.annotation_contributors(
  _annotation CHAR(24) NOT NULL,
  _user CHAR(24) NOT NULL,
  PRIMARY KEY (_annotation, _user)
);

CREATE TABLE webknossos.meshes(
  _id CHAR(24) PRIMARY KEY,
  _annotation CHAR(24) NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  position webknossos.VECTOR3 NOT NULL,
  data TEXT,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE webknossos.publications(
  _id CHAR(24) PRIMARY KEY,
  publicationDate TIMESTAMPTZ,
  imageUrl VARCHAR(2048),
  title VARCHAR(2048),
  description TEXT,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE webknossos.dataSets(
  _id CHAR(24) PRIMARY KEY,
  _dataStore VARCHAR(256) NOT NULL,
  _organization CHAR(24) NOT NULL,
  _publication CHAR(24),
  _uploader CHAR(24),
  inboxSourceHash INT,
  defaultViewConfiguration JSONB,
  adminViewConfiguration JSONB,
  description TEXT,
  displayName VARCHAR(256),
  isPublic BOOLEAN NOT NULL DEFAULT false,
  isUsable BOOLEAN NOT NULL DEFAULT false,
  name VARCHAR(256) NOT NULL,
  scale webknossos.VECTOR3,
  status VARCHAR(1024) NOT NULL DEFAULT '',
  sharingToken CHAR(256),
  logoUrl VARCHAR(2048),
  sortingKey TIMESTAMPTZ NOT NULL,
  details JSONB,
  tags VARCHAR(256)[] NOT NULL DEFAULT '{}',
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (name, _organization),
  CONSTRAINT defaultViewConfigurationIsJsonObject CHECK(jsonb_typeof(defaultViewConfiguration) = 'object'),
  CONSTRAINT adminViewConfigurationIsJsonObject CHECK(jsonb_typeof(adminViewConfiguration) = 'object'),
  CONSTRAINT detailsIsJsonObject CHECK(jsonb_typeof(details) = 'object')
);

CREATE TYPE webknossos.DATASET_LAYER_CATEGORY AS ENUM ('color', 'mask', 'segmentation');
CREATE TYPE webknossos.DATASET_LAYER_ELEMENT_CLASS AS ENUM ('uint8', 'uint16', 'uint24', 'uint32', 'uint64', 'float', 'double', 'int8', 'int16', 'int32', 'int64');
CREATE TABLE webknossos.dataSet_layers(
  _dataSet CHAR(24) NOT NULL,
  name VARCHAR(256) NOT NULL,
  category webknossos.DATASET_LAYER_CATEGORY NOT NULL,
  elementClass webknossos.DATASET_LAYER_ELEMENT_CLASS NOT NULL,
  boundingBox webknossos.BOUNDING_BOX NOT NULL,
  largestSegmentId BIGINT,
  mappings VARCHAR(256)[],
  defaultViewConfiguration JSONB,
  adminViewConfiguration JSONB,
  PRIMARY KEY(_dataSet, name),
  CONSTRAINT defaultViewConfigurationIsJsonObject CHECK(jsonb_typeof(defaultViewConfiguration) = 'object'),
  CONSTRAINT adminViewConfigurationIsJsonObject CHECK(jsonb_typeof(adminViewConfiguration) = 'object')
);

CREATE TABLE webknossos.dataSet_allowedTeams(
  _dataSet CHAR(24) NOT NULL,
  _team CHAR(24) NOT NULL,
  PRIMARY KEY (_dataSet, _team)
);

CREATE TABLE webknossos.dataSet_resolutions(
  _dataSet CHAR(24) NOT NULL,
  dataLayerName VARCHAR(256),
  resolution webknossos.VECTOR3 NOT NULL,
  PRIMARY KEY (_dataSet, dataLayerName, resolution)
);

CREATE TABLE webknossos.dataSet_lastUsedTimes(
  _dataSet CHAR(24) NOT NULL,
  _user CHAR(24) NOT NULL,
  lastUsedTime TIMESTAMPTZ NOT NULL
);

CREATE TYPE webknossos.DATASTORE_TYPE AS ENUM ('webknossos-store');
CREATE TABLE webknossos.dataStores(
  name VARCHAR(256) PRIMARY KEY NOT NULL CHECK (name ~* '^[A-Za-z0-9\-_\.]+$'),
  url VARCHAR(512) UNIQUE NOT NULL CHECK (url ~* '^https?://[a-z0-9\.]+.*$'),
  publicUrl VARCHAR(512) UNIQUE NOT NULL CHECK (publicUrl ~* '^https?://[a-z0-9\.]+.*$'),
  key VARCHAR(1024) NOT NULL,
  isScratch BOOLEAN NOT NULL DEFAULT false,
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  isConnector BOOLEAN NOT NULL DEFAULT false,
  allowsUpload BOOLEAN NOT NULL DEFAULT true,
  onlyAllowedOrganization CHAR(24)
);

CREATE TABLE webknossos.tracingStores(
  name VARCHAR(256) PRIMARY KEY NOT NULL CHECK (name ~* '^[A-Za-z0-9\-_\.]+$'),
  url VARCHAR(512) UNIQUE NOT NULL CHECK (url ~* '^https?://[a-z0-9\.]+.*$'),
  publicUrl VARCHAR(512) UNIQUE NOT NULL CHECK (publicUrl ~* '^https?://[a-z0-9\.]+.*$'),
  key VARCHAR(1024) NOT NULL,
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE webknossos.projects(
  _id CHAR(24) PRIMARY KEY,
  _organization CHAR(24) NOT NULL,
  _team CHAR(24) NOT NULL,
  _owner CHAR(24) NOT NULL,
  name VARCHAR(256) NOT NULL CHECK (name ~* '^.{3,}$'), -- Unique among non-deleted, enforced in scala
  priority BIGINT NOT NULL DEFAULT 100,
  paused BOOLEAN NOT NULL DEFAULT false,
  expectedTime BIGINT,
  isBlacklistedFromReport BOOLEAN NOT NULL DEFAULT false,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE webknossos.scripts(
  _id CHAR(24) PRIMARY KEY,
  _owner CHAR(24) NOT NULL,
  name VARCHAR(256) NOT NULL CHECK (name ~* '^[A-Za-z0-9\-_\. ß]+$'),
  gist VARCHAR(1024) NOT NULL,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  CHECK (gist ~* '^https?://[a-z0-9\-_\.]+.*$')
);

CREATE TYPE webknossos.TASKTYPE_MODES AS ENUM ('orthogonal', 'flight', 'oblique', 'volume');
CREATE TYPE webknossos.TASKTYPE_TRACINGTYPES AS ENUM ('skeleton', 'volume', 'hybrid');
CREATE TABLE webknossos.taskTypes(
  _id CHAR(24) PRIMARY KEY,
  _organization CHAR(24) NOT NULL,
  _team CHAR(24) NOT NULL,
  summary VARCHAR(256) NOT NULL,
  description TEXT NOT NULL,
  settings_allowedModes webknossos.TASKTYPE_MODES[] NOT NULL DEFAULT '{orthogonal, flight, oblique}',
  settings_preferredMode webknossos.TASKTYPE_MODES DEFAULT 'orthogonal',
  settings_branchPointsAllowed BOOLEAN NOT NULL,
  settings_somaClickingAllowed BOOLEAN NOT NULL,
  settings_volumeInterpolationAllowed BOOLEAN NOT NULL DEFAULT false,
  settings_mergerMode BOOLEAN NOT NULL DEFAULT false,
  settings_resolutionRestrictions_min INT DEFAULT NULL,
  settings_resolutionRestrictions_max INT DEFAULT NULL,
  recommendedConfiguration JSONB,
  tracingType webknossos.TASKTYPE_TRACINGTYPES NOT NULL DEFAULT 'skeleton',
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT recommendedConfigurationIsJsonObject CHECK(jsonb_typeof(recommendedConfiguration) = 'object'),
  UNIQUE (summary, _organization)
);

CREATE TABLE webknossos.tasks(
  _id CHAR(24) PRIMARY KEY,
  _project CHAR(24) NOT NULL,
  _script CHAR(24),
  _taskType CHAR(24) NOT NULL,
  neededExperience_domain VARCHAR(256) NOT NULL CHECK (neededExperience_domain ~* '^.{2,}$'),
  neededExperience_value INT NOT NULL,
  totalInstances BIGINT NOT NULL,
  openInstances BIGINT NOT NULL,
  tracingTime BIGINT,
  boundingBox webknossos.BOUNDING_BOX,
  editPosition webknossos.VECTOR3 NOT NULL,
  editRotation webknossos.VECTOR3 NOT NULL,
  creationInfo VARCHAR(512),
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT openInstancesLargeEnoughCheck CHECK (openInstances >= 0)
);

CREATE TABLE webknossos.experienceDomains(
  domain VARCHAR(256) NOT NULL,
  _organization CHAR(24) NOT NULL,
  CONSTRAINT primarykey__domain_orga PRIMARY KEY (domain,_organization)
);

CREATE TABLE webknossos.teams(
  _id CHAR(24) PRIMARY KEY,
  _organization CHAR(24) NOT NULL,
  name VARCHAR(256) NOT NULL CHECK (name ~* '^[A-Za-z0-9\-_\. ß]+$'),
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isOrganizationTeam BOOLEAN NOT NULL DEFAULT false,
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (name, _organization)
);

CREATE TABLE webknossos.timespans(
  _id CHAR(24) PRIMARY KEY,
  _user CHAR(24) NOT NULL,
  _annotation CHAR(24),
  time BIGINT NOT NULL,
  lastUpdate TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  numberOfUpdates BIGINT NOT NULL DEFAULT 1,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TYPE webknossos.PRICING_PLANS AS ENUM ('Basic', 'Premium', 'Pilot', 'Custom');
CREATE TABLE webknossos.organizations(
  _id CHAR(24) PRIMARY KEY,
  name VARCHAR(256) NOT NULL UNIQUE,
  additionalInformation VARCHAR(2048) NOT NULL DEFAULT '',
  logoUrl VARCHAR(2048) NOT NULL DEFAULT '',
  displayName VARCHAR(1024) NOT NULL DEFAULT '',
  newUserMailingList VARCHAR(512) NOT NULL DEFAULT '',
  overTimeMailingList VARCHAR(512) NOT NULL DEFAULT '',
  enableAutoVerify BOOLEAN NOT NULL DEFAULT false,
  pricingPlan webknossos.PRICING_PLANS NOT NULL DEFAULT 'Custom',
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TYPE webknossos.USER_PASSWORDINFO_HASHERS AS ENUM ('SCrypt');
CREATE TABLE webknossos.users(
  _id CHAR(24) PRIMARY KEY,
  _multiUser CHAR(24) NOT NULL,
  _organization CHAR(24) NOT NULL,
  firstName VARCHAR(256) NOT NULL, -- CHECK (firstName ~* '^[A-Za-z0-9\-_ ]+$'),
  lastName VARCHAR(256) NOT NULL, -- CHECK (lastName ~* '^[A-Za-z0-9\-_ ]+$'),
  lastActivity TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  userConfiguration JSONB NOT NULL,
  isDeactivated BOOLEAN NOT NULL DEFAULT false,
  isAdmin BOOLEAN NOT NULL DEFAULT false,
  isDatasetManager BOOLEAN NOT NULL DEFAULT false,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  lastTaskTypeId CHAR(24) DEFAULT NULL,
  isUnlisted BOOLEAN NOT NULL DEFAULT FALSE,
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (_multiUser, _organization),
  CONSTRAINT userConfigurationIsJsonObject CHECK(jsonb_typeof(userConfiguration) = 'object')
);

CREATE TABLE webknossos.user_team_roles(
  _user CHAR(24) NOT NULL,
  _team CHAR(24) NOT NULL,
  isTeamManager BOOLEAN NOT NULL DEFAULT false,
  PRIMARY KEY (_user, _team)
);

CREATE TABLE webknossos.user_experiences(
  _user CHAR(24) NOT NULL,
  domain VARCHAR(256) NOT NULL,
  value INT NOT NULL DEFAULT 1,
  PRIMARY KEY (_user, domain)
);

CREATE TABLE webknossos.user_dataSetConfigurations(
  _user CHAR(24) NOT NULL,
  _dataSet CHAR(24) NOT NULL,
  viewConfiguration JSONB NOT NULL,
  PRIMARY KEY (_user, _dataSet),
  CONSTRAINT viewConfigurationIsJsonObject CHECK(jsonb_typeof(viewConfiguration) = 'object')
);

CREATE TABLE webknossos.user_dataSetLayerConfigurations(
  _user CHAR(24) NOT NULL,
  _dataSet CHAR(24) NOT NULL,
  layerName VARCHAR(256) NOT NULL,
  viewConfiguration JSONB NOT NULL,
  PRIMARY KEY (_user, _dataSet, layerName),
  CONSTRAINT viewConfigurationIsJsonObject CHECK(jsonb_typeof(viewConfiguration) = 'object')
);


CREATE TYPE webknossos.THEME AS ENUM ('light', 'dark', 'auto');
CREATE TABLE webknossos.multiUsers(
  _id CHAR(24) PRIMARY KEY,
  email VARCHAR(512) NOT NULL UNIQUE CHECK (email ~* '^.+@.+$'),
  passwordInfo_hasher webknossos.USER_PASSWORDINFO_HASHERS NOT NULL DEFAULT 'SCrypt',
  passwordInfo_password VARCHAR(512) NOT NULL,
  isSuperUser BOOLEAN NOT NULL DEFAULT false,
  novelUserExperienceInfos JSONB NOT NULL DEFAULT '{}'::json,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  selectedTheme webknossos.THEME NOT NULL DEFAULT 'auto',
  _lastLoggedInIdentity CHAR(24) DEFAULT NULL,
  isDeleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT nuxInfoIsJsonObject CHECK(jsonb_typeof(novelUserExperienceInfos) = 'object')
);


CREATE TYPE webknossos.TOKEN_TYPES AS ENUM ('Authentication', 'DataStore', 'ResetPassword');
CREATE TYPE webknossos.USER_LOGININFO_PROVDERIDS AS ENUM ('credentials');
CREATE TABLE webknossos.tokens(
  _id CHAR(24) PRIMARY KEY,
  value Text NOT NULL,
  loginInfo_providerID webknossos.USER_LOGININFO_PROVDERIDS NOT NULL,
  loginInfo_providerKey VARCHAR(512) NOT NULL,
  lastUsedDateTime TIMESTAMPTZ NOT NULL,
  expirationDateTime TIMESTAMPTZ NOT NULL,
  idleTimeout BIGINT,
  tokenType webknossos.TOKEN_TYPES NOT NULL,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE webknossos.maintenance(
  maintenanceExpirationTime TIMESTAMPTZ NOT NULL
);
INSERT INTO webknossos.maintenance(maintenanceExpirationTime) values('2000-01-01 00:00:00');


CREATE TABLE webknossos.workers(
  _id CHAR(24) PRIMARY KEY,
  _dataStore VARCHAR(256) NOT NULL,
  key VARCHAR(1024) NOT NULL UNIQUE,
  maxParallelJobs INT NOT NULL DEFAULT 1,
  lastHeartBeat TIMESTAMPTZ NOT NULL DEFAULT '2000-01-01T00:00:00Z',
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);


CREATE TYPE webknossos.JOB_STATE AS ENUM ('PENDING', 'STARTED', 'SUCCESS', 'FAILURE', 'CANCELLED');

CREATE TABLE webknossos.jobs(
  _id CHAR(24) PRIMARY KEY,
  _owner CHAR(24) NOT NULL,
  _dataStore VARCHAR(256) NOT NULL,
  command TEXT NOT NULL,
  commandArgs JSONB NOT NULL,
  state webknossos.JOB_STATE NOT NULL DEFAULT 'PENDING', -- always updated by the worker
  manualState webknossos.JOB_STATE, -- set by the user or admin
  _worker CHAR(24),
  latestRunId VARCHAR(1024),
  returnValue Text,
  started TIMESTAMPTZ,
  ended TIMESTAMPTZ,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);


CREATE TABLE webknossos.invites(
  _id CHAR(24) PRIMARY KEY,
  tokenValue Text NOT NULL,
  _organization CHAR(24) NOT NULL,
  autoActivate BOOLEAN NOT NULL,
  expirationDateTime TIMESTAMPTZ NOT NULL,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE webknossos.annotation_privateLinks(
  _id CHAR(24) PRIMARY KEY,
  _annotation CHAR(24) NOT NULL,
  accessToken Text NOT NULL UNIQUE,
  expirationDateTime TIMESTAMPTZ,
  isDeleted BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE webknossos.shortLinks(
  _id CHAR(24) PRIMARY KEY,
  key CHAR(16) NOT NULL UNIQUE,
  longLink Text NOT NULL
);


CREATE TYPE webknossos.VOXELYTICS_RUN_STATE AS ENUM ('PENDING', 'SKIPPED', 'RUNNING', 'COMPLETE', 'FAILED', 'CANCELLED', 'STALE');

CREATE TABLE webknossos.voxelytics_artifacts (
    _id CHAR(24) NOT NULL,
    _task CHAR(24) NOT NULL,
    name VARCHAR(512) NOT NULL,
    path TEXT NOT NULL,
    fileSize INT8 NOT NULL,
    inodeCount INT8 NOT NULL,
    version TEXT NOT NULL DEFAULT '0',
    metadata JSONB,
    PRIMARY KEY (_id),
    UNIQUE (_task, name),
    CONSTRAINT metadataIsJsonObject CHECK(jsonb_typeof(metadata) = 'object')
);

CREATE TABLE webknossos.voxelytics_runs (
    _id CHAR(24) NOT NULL,
    _organization CHAR(24) NOT NULL,
    _user CHAR(24) NOT NULL,
    name VARCHAR(2048) NOT NULL,
    username TEXT NOT NULL,
    hostname TEXT NOT NULL,
    voxelyticsVersion TEXT NOT NULL,
    workflow_hash VARCHAR(512) NOT NULL,
    workflow_yamlContent TEXT,
    workflow_config JSONB,
    PRIMARY KEY (_id),
    UNIQUE (_organization, name),
    CONSTRAINT workflowConfigIsJsonObject CHECK(jsonb_typeof(workflow_config) = 'object')
);

CREATE TABLE webknossos.voxelytics_tasks (
    _id CHAR(24) NOT NULL,
    _run CHAR(24) NOT NULL,
    name varCHAR(2048) NOT NULL,
    task varCHAR(512) NOT NULL,
    config JSONB NOT NULL,
    PRIMARY KEY (_id),
    UNIQUE (_run, name),
    CONSTRAINT configIsJsonObject CHECK(jsonb_typeof(config) = 'object')
);

CREATE TABLE webknossos.voxelytics_chunks (
    _id CHAR(24) NOT NULL,
    _task CHAR(24) NOT NULL,
    executionId VARCHAR(2048) NOT NULL,
    chunkName VARCHAR(2048) NOT NULL,
    PRIMARY KEY (_id),
    UNIQUE (_task, executionId, chunkName)
);

CREATE TABLE webknossos.voxelytics_workflows (
    _organization CHAR(24) NOT NULL,
    hash VARCHAR(512) NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (_organization, hash)
);

CREATE TABLE webknossos.voxelytics_runStateChangeEvents (
    _run CHAR(24) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    state webknossos.VOXELYTICS_RUN_STATE NOT NULL,
    PRIMARY KEY (_run, timestamp)
);

CREATE TABLE webknossos.voxelytics_runHeartbeatEvents (
    _run CHAR(24) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (_run)
);

CREATE TABLE webknossos.voxelytics_taskStateChangeEvents (
    _task CHAR(24) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    state webknossos.VOXELYTICS_RUN_STATE NOT NULL,
    PRIMARY KEY (_task, timestamp)
);

CREATE TABLE webknossos.voxelytics_chunkStateChangeEvents (
    _chunk CHAR(24) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    state webknossos.VOXELYTICS_RUN_STATE NOT NULL,
    PRIMARY KEY (_chunk, timestamp)
);

CREATE TABLE webknossos.voxelytics_chunkProfilingEvents (
    _chunk CHAR(24) NOT NULL,
    hostname TEXT NOT NULL,
    pid INT8 NOT NULL,
    memory FLOAT NOT NULL,
    cpuUser FLOAT NOT NULL,
    cpuSystem FLOAT NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (_chunk, timestamp)
);

CREATE TABLE webknossos.voxelytics_artifactFileChecksumEvents (
    _artifact CHAR(24) NOT NULL,
    path TEXT NOT NULL,
    resolvedPath TEXT NOT NULL,
    checksumMethod VARCHAR(512) NOT NULL,
    checksum VARCHAR(512) NOT NULL,
    fileSize INT8 NOT NULL,
    lastModified TIMESTAMPTZ NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (_artifact, path, timestamp)
);


CREATE VIEW webknossos.annotations_ AS SELECT * FROM webknossos.annotations WHERE NOT isDeleted;
CREATE VIEW webknossos.meshes_ AS SELECT * FROM webknossos.meshes WHERE NOT isDeleted;
CREATE VIEW webknossos.publications_ AS SELECT * FROM webknossos.publications WHERE NOT isDeleted;
CREATE VIEW webknossos.dataSets_ AS SELECT * FROM webknossos.dataSets WHERE NOT isDeleted;
CREATE VIEW webknossos.dataStores_ AS SELECT * FROM webknossos.dataStores WHERE NOT isDeleted;
CREATE VIEW webknossos.tracingStores_ AS SELECT * FROM webknossos.tracingStores WHERE NOT isDeleted;
CREATE VIEW webknossos.projects_ AS SELECT * FROM webknossos.projects WHERE NOT isDeleted;
CREATE VIEW webknossos.scripts_ AS SELECT * FROM webknossos.scripts WHERE NOT isDeleted;
CREATE VIEW webknossos.taskTypes_ AS SELECT * FROM webknossos.taskTypes WHERE NOT isDeleted;
CREATE VIEW webknossos.tasks_ AS SELECT * FROM webknossos.tasks WHERE NOT isDeleted;
CREATE VIEW webknossos.teams_ AS SELECT * FROM webknossos.teams WHERE NOT isDeleted;
CREATE VIEW webknossos.timespans_ AS SELECT * FROM webknossos.timespans WHERE NOT isDeleted;
CREATE VIEW webknossos.organizations_ AS SELECT * FROM webknossos.organizations WHERE NOT isDeleted;
CREATE VIEW webknossos.users_ AS SELECT * FROM webknossos.users WHERE NOT isDeleted;
CREATE VIEW webknossos.multiUsers_ AS SELECT * FROM webknossos.multiUsers WHERE NOT isDeleted;
CREATE VIEW webknossos.tokens_ AS SELECT * FROM webknossos.tokens WHERE NOT isDeleted;
CREATE VIEW webknossos.jobs_ AS SELECT * FROM webknossos.jobs WHERE NOT isDeleted;
CREATE VIEW webknossos.workers_ AS SELECT * FROM webknossos.workers WHERE NOT isDeleted;
CREATE VIEW webknossos.invites_ AS SELECT * FROM webknossos.invites WHERE NOT isDeleted;
CREATE VIEW webknossos.organizationTeams AS SELECT * FROM webknossos.teams WHERE isOrganizationTeam AND NOT isDeleted;
CREATE VIEW webknossos.annotation_privateLinks_ as SELECT * FROM webknossos.annotation_privateLinks WHERE NOT isDeleted;

CREATE VIEW webknossos.userInfos AS
SELECT
u._id AS _user, m.email, u.firstName, u.lastname, o.displayName AS organization_displayName,
u.isDeactivated, u.isDatasetManager, u.isAdmin, m.isSuperUser,
u._organization, o.name AS organization_name, u.created AS user_created,
m.created AS multiuser_created, u._multiUser, m._lastLoggedInIdentity, u.lastActivity
FROM webknossos.users_ u
JOIN webknossos.organizations_ o ON u._organization = o._id
JOIN webknossos.multiUsers_ m on u._multiUser = m._id;


CREATE INDEX ON webknossos.annotations(_user, isDeleted);
CREATE INDEX ON webknossos.annotations(_task, isDeleted);
CREATE INDEX ON webknossos.annotations(typ, state, isDeleted);
CREATE INDEX ON webknossos.annotations(_user, _task, isDeleted);
CREATE INDEX ON webknossos.annotations(_task, typ, isDeleted);
CREATE INDEX ON webknossos.annotations(typ, isDeleted);
CREATE INDEX ON webknossos.dataSets(name);
CREATE INDEX ON webknossos.tasks(_project);
CREATE INDEX ON webknossos.tasks(isDeleted);
CREATE INDEX ON webknossos.tasks(_project, isDeleted);
CREATE INDEX ON webknossos.tasks(neededExperience_domain, neededExperience_value);
CREATE INDEX ON webknossos.tasks(_taskType);
CREATE INDEX ON webknossos.timespans(_user);
CREATE INDEX ON webknossos.timespans(_annotation);
CREATE INDEX ON webknossos.users(_multiUser);
CREATE INDEX ON webknossos.multiUsers(email);
CREATE INDEX ON webknossos.projects(name);
CREATE INDEX ON webknossos.projects(_team);
CREATE INDEX ON webknossos.projects(name, isDeleted);
CREATE INDEX ON webknossos.projects(_team, isDeleted);
CREATE INDEX ON webknossos.invites(tokenValue);
CREATE INDEX ON webknossos.annotation_privateLinks(accessToken);
CREATE INDEX ON webknossos.shortLinks(key);

ALTER TABLE webknossos.annotations
  ADD CONSTRAINT task_ref FOREIGN KEY(_task) REFERENCES webknossos.tasks(_id) ON DELETE SET NULL DEFERRABLE,
  ADD CONSTRAINT team_ref FOREIGN KEY(_team) REFERENCES webknossos.teams(_id) DEFERRABLE,
  ADD CONSTRAINT user_ref FOREIGN KEY(_user) REFERENCES webknossos.users(_id) DEFERRABLE,
  ADD CONSTRAINT dataSet_ref FOREIGN KEY(_dataSet) REFERENCES webknossos.dataSets(_id) DEFERRABLE,
  ADD CONSTRAINT publication_ref FOREIGN KEY(_publication) REFERENCES webknossos.publications(_id) DEFERRABLE;
ALTER TABLE webknossos.annotation_sharedTeams
    ADD CONSTRAINT annotation_ref FOREIGN KEY(_annotation) REFERENCES webknossos.annotations(_id) ON DELETE CASCADE DEFERRABLE,
    ADD CONSTRAINT team_ref FOREIGN KEY(_team) REFERENCES webknossos.teams(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.annotation_contributors
    ADD CONSTRAINT annotation_ref FOREIGN KEY(_annotation) REFERENCES webknossos.annotations(_id) ON DELETE CASCADE DEFERRABLE,
    ADD CONSTRAINT user_ref FOREIGN KEY(_user) REFERENCES webknossos.users(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.meshes
  ADD CONSTRAINT annotation_ref FOREIGN KEY(_annotation) REFERENCES webknossos.annotations(_id) DEFERRABLE;
ALTER TABLE webknossos.dataSets
  ADD CONSTRAINT organization_ref FOREIGN KEY(_organization) REFERENCES webknossos.organizations(_id) DEFERRABLE,
  ADD CONSTRAINT dataStore_ref FOREIGN KEY(_dataStore) REFERENCES webknossos.dataStores(name) DEFERRABLE,
  ADD CONSTRAINT uploader_ref FOREIGN KEY(_uploader) REFERENCES webknossos.users(_id) DEFERRABLE,
  ADD CONSTRAINT publication_ref FOREIGN KEY(_publication) REFERENCES webknossos.publications(_id) DEFERRABLE;
ALTER TABLE webknossos.dataSet_layers
  ADD CONSTRAINT dataSet_ref FOREIGN KEY(_dataSet) REFERENCES webknossos.dataSets(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.dataSet_allowedTeams
  ADD CONSTRAINT dataSet_ref FOREIGN KEY(_dataSet) REFERENCES webknossos.dataSets(_id) ON DELETE CASCADE DEFERRABLE,
  ADD CONSTRAINT team_ref FOREIGN KEY(_team) REFERENCES webknossos.teams(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.dataSet_resolutions
  ADD CONSTRAINT dataSet_ref FOREIGN KEY(_dataSet) REFERENCES webknossos.dataSets(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.projects
  ADD CONSTRAINT team_ref FOREIGN KEY(_team) REFERENCES webknossos.teams(_id) DEFERRABLE,
  ADD CONSTRAINT user_ref FOREIGN KEY(_owner) REFERENCES webknossos.users(_id) DEFERRABLE;
ALTER TABLE webknossos.scripts
  ADD CONSTRAINT user_ref FOREIGN KEY(_owner) REFERENCES webknossos.users(_id) DEFERRABLE;
ALTER TABLE webknossos.taskTypes
  ADD CONSTRAINT team_ref FOREIGN KEY(_team) REFERENCES webknossos.teams(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.tasks
  ADD CONSTRAINT project_ref FOREIGN KEY(_project) REFERENCES webknossos.projects(_id) DEFERRABLE,
  ADD CONSTRAINT script_ref FOREIGN KEY(_script) REFERENCES webknossos.scripts(_id) ON DELETE SET NULL DEFERRABLE;
ALTER TABLE webknossos.teams
  ADD CONSTRAINT organization_ref FOREIGN KEY(_organization) REFERENCES webknossos.organizations(_id) DEFERRABLE;
ALTER TABLE webknossos.timespans
  ADD CONSTRAINT user_ref FOREIGN KEY(_user) REFERENCES webknossos.users(_id) ON DELETE CASCADE DEFERRABLE,
  ADD CONSTRAINT annotation_ref FOREIGN KEY(_annotation) REFERENCES webknossos.annotations(_id) ON DELETE SET NULL DEFERRABLE;
ALTER TABLE webknossos.users
  ADD CONSTRAINT organization_ref FOREIGN KEY(_organization) REFERENCES webknossos.organizations(_id) DEFERRABLE;
ALTER TABLE webknossos.user_team_roles
  ADD CONSTRAINT user_ref FOREIGN KEY(_user) REFERENCES webknossos.users(_id) ON DELETE CASCADE DEFERRABLE,
  ADD CONSTRAINT team_ref FOREIGN KEY(_team) REFERENCES webknossos.teams(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.user_experiences
  ADD CONSTRAINT user_ref FOREIGN KEY(_user) REFERENCES webknossos.users(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.user_dataSetConfigurations
  ADD CONSTRAINT user_ref FOREIGN KEY(_user) REFERENCES webknossos.users(_id) ON DELETE CASCADE DEFERRABLE,
  ADD CONSTRAINT dataSet_ref FOREIGN KEY(_dataSet) REFERENCES webknossos.dataSets(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.user_dataSetLayerConfigurations
  ADD CONSTRAINT user_ref FOREIGN KEY(_user) REFERENCES webknossos.users(_id) ON DELETE CASCADE DEFERRABLE,
  ADD CONSTRAINT dataSet_ref FOREIGN KEY(_dataSet) REFERENCES webknossos.dataSets(_id) ON DELETE CASCADE DEFERRABLE;
ALTER TABLE webknossos.multiUsers
  ADD CONSTRAINT lastLoggedInIdentity_ref FOREIGN KEY(_lastLoggedInIdentity) REFERENCES webknossos.users(_id) ON DELETE SET NULL;
ALTER TABLE webknossos.experienceDomains
  ADD CONSTRAINT organization_ref FOREIGN KEY(_organization) REFERENCES webknossos.organizations(_id) DEFERRABLE;
ALTER TABLE webknossos.jobs
  ADD CONSTRAINT owner_ref FOREIGN KEY(_owner) REFERENCES webknossos.users(_id) DEFERRABLE,
  ADD CONSTRAINT dataStore_ref FOREIGN KEY(_dataStore) REFERENCES webknossos.dataStores(name) DEFERRABLE,
  ADD CONSTRAINT worker_ref FOREIGN KEY(_worker) REFERENCES webknossos.workers(_id) DEFERRABLE;
ALTER TABLE webknossos.workers
  ADD CONSTRAINT dataStore_ref FOREIGN KEY(_dataStore) REFERENCES webknossos.dataStores(name) DEFERRABLE;
ALTER TABLE webknossos.annotation_privateLinks
  ADD CONSTRAINT annotation_ref FOREIGN KEY(_annotation) REFERENCES webknossos.annotations(_id) DEFERRABLE;

ALTER TABLE webknossos.voxelytics_artifacts
  ADD FOREIGN KEY (_task) REFERENCES webknossos.voxelytics_tasks(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_runs
  ADD FOREIGN KEY (_organization) REFERENCES webknossos.organizations(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE,
  ADD FOREIGN KEY (_organization, workflow_hash) REFERENCES webknossos.voxelytics_workflows(_organization, hash) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_tasks
  ADD FOREIGN KEY (_run) REFERENCES webknossos.voxelytics_runs(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_chunks
  ADD FOREIGN KEY (_task) REFERENCES webknossos.voxelytics_tasks(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_workflows
  ADD FOREIGN KEY (_organization) REFERENCES webknossos.organizations(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_runStateChangeEvents
  ADD FOREIGN KEY (_run) REFERENCES webknossos.voxelytics_runs(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_runHeartbeatEvents
  ADD FOREIGN KEY (_run) REFERENCES webknossos.voxelytics_runs(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_taskStateChangeEvents
  ADD FOREIGN KEY (_task) REFERENCES webknossos.voxelytics_tasks(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_chunkStateChangeEvents
  ADD FOREIGN KEY (_chunk) REFERENCES webknossos.voxelytics_chunks(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_chunkProfilingEvents
  ADD FOREIGN KEY (_chunk) REFERENCES webknossos.voxelytics_chunks(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;
ALTER TABLE webknossos.voxelytics_artifactFileChecksumEvents
  ADD FOREIGN KEY (_artifact) REFERENCES webknossos.voxelytics_artifacts(_id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE;


CREATE FUNCTION webknossos.countsAsTaskInstance(a webknossos.annotations) RETURNS BOOLEAN AS $$
  BEGIN
    RETURN (a.state != 'Cancelled' AND a.isDeleted = false AND a.typ = 'Task');
  END;
$$ LANGUAGE plpgsql;


CREATE FUNCTION webknossos.onUpdateTask() RETURNS trigger AS $$
  BEGIN
    IF NEW.totalInstances <> OLD.totalInstances THEN
      UPDATE webknossos.tasks SET openInstances = openInstances + (NEW.totalInstances - OLD.totalInstances) WHERE _id = NEW._id;
    END IF;
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER onUpdateTaskTrigger
AFTER UPDATE ON webknossos.tasks
FOR EACH ROW EXECUTE PROCEDURE webknossos.onUpdateTask();


CREATE FUNCTION webknossos.onInsertAnnotation() RETURNS trigger AS $$
  BEGIN
    IF (NEW.typ = 'Task') AND (NEW.isDeleted = false) AND (NEW.state != 'Cancelled') THEN
      UPDATE webknossos.tasks SET openInstances = openInstances - 1 WHERE _id = NEW._task;
    END IF;
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER onInsertAnnotationTrigger
AFTER INSERT ON webknossos.annotations
FOR EACH ROW EXECUTE PROCEDURE webknossos.onInsertAnnotation();



CREATE OR REPLACE FUNCTION webknossos.onUpdateAnnotation() RETURNS trigger AS $$
  BEGIN
    IF (NEW._task != OLD._task) OR (NEW.typ != OLD.typ) THEN
        RAISE EXCEPTION 'annotation columns _task and typ are immutable';
    END IF;
    IF (webknossos.countsAsTaskInstance(OLD) AND NOT webknossos.countsAsTaskInstance(NEW))
    THEN
      UPDATE webknossos.tasks SET openInstances = openInstances + 1 WHERE _id = NEW._task;
    END IF;
    IF (NOT webknossos.countsAsTaskInstance(OLD) AND webknossos.countsAsTaskInstance(NEW))
    THEN
      UPDATE webknossos.tasks SET openInstances = openInstances - 1 WHERE _id = NEW._task;
    END IF;
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER onUpdateAnnotationTrigger
AFTER UPDATE ON webknossos.annotations
FOR EACH ROW EXECUTE PROCEDURE webknossos.onUpdateAnnotation();


CREATE FUNCTION webknossos.onDeleteAnnotation() RETURNS trigger AS $$
  BEGIN
    IF (OLD.typ = 'Task') AND (OLD.isDeleted = false) AND (OLD.state != 'Cancelled') THEN
      UPDATE webknossos.tasks SET openInstances = openInstances + 1 WHERE _id = OLD._task;
    END IF;
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER onDeleteAnnotationTrigger
AFTER DELETE ON webknossos.annotations
FOR EACH ROW EXECUTE PROCEDURE webknossos.onDeleteAnnotation();

