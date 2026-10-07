-- A version is the pair (content hash, uploader) and the jar is kept once per content (WI-54,
-- ADR-020). pipeline_artifact stays the version: its id, and so every reference from a definition,
-- a trigger or a run, is unchanged. The bytes (and so the size, which is a property of the content)
-- move to artifact_content, keyed by the content hash.
--
-- Every artifact stored before this migration is the only version of its content: nothing is split
-- or merged, and each jar is copied unchanged, so it still reads back byte for byte under its hash.
CREATE TABLE artifact_content (
    content_hash TEXT   PRIMARY KEY CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    content      BYTEA  NOT NULL,
    size_bytes   BIGINT NOT NULL CHECK (size_bytes >= 0)
);

INSERT INTO artifact_content (content_hash, content, size_bytes)
SELECT content_hash, content, size_bytes FROM pipeline_artifact;

ALTER TABLE pipeline_artifact DROP CONSTRAINT pipeline_artifact_content_hash_key;
ALTER TABLE pipeline_artifact DROP COLUMN content;
ALTER TABLE pipeline_artifact DROP COLUMN size_bytes;

-- A content row is removed with its last version, in the same transaction (the Engine does that);
-- RESTRICT keeps a version from ever pointing at content that is gone.
ALTER TABLE pipeline_artifact
    ADD CONSTRAINT pipeline_artifact_content_fk
        FOREIGN KEY (content_hash) REFERENCES artifact_content (content_hash) ON DELETE RESTRICT,
    ADD CONSTRAINT pipeline_artifact_version_key UNIQUE (content_hash, uploaded_by);
