-- One-time repair of legacy Agent placeholders. User authorized using the initial user content.
-- The old writer incorrectly marked placeholders USER; other names are never changed.
-- Runs before the new writer starts, so explicitly named new conversations stay USER.
UPDATE conversation c
SET title = COALESCE((
    SELECT LEFT(TRIM(REPLACE(REPLACE(REPLACE(m.content, CHAR(13), ' '), CHAR(10), ' '), CHAR(9), ' ')), 32)
    FROM conversation_message m
    WHERE m.conversation_id=c.id AND m.user_id=c.user_id AND m.role='USER'
      AND TRIM(REPLACE(REPLACE(REPLACE(COALESCE(m.content,''), CHAR(13), ' '), CHAR(10), ' '), CHAR(9), ' '))<>''
    ORDER BY m.seq LIMIT 1
), title), title_source='AUTO'
WHERE c.creation_mode='AGENT' AND c.archived=0 AND c.title='新的创作' AND c.title_source IN ('USER','AUTO')
  AND EXISTS (SELECT 1 FROM agent_session s WHERE s.conversation_id=c.id AND s.user_id=c.user_id);
