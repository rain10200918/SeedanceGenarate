package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.StoredImageReferences;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Exact owned Artifact identity, independently queried to avoid Store/Gateway dependency cycles. */
@Component
@RequiredArgsConstructor
public class AgentVideoReference {
    private final JdbcTemplate jdbc;
    private final StoredImageReferences references;
    private final ObjectMapper json;
    public ObjectNode resolve(Long owner,String session,JsonNode ref) {
        validateShape(ref);
        var rows=jdbc.query("SELECT a.task_id,a.title,t.artifact_key FROM agent_artifact_version a JOIN agent_session s ON s.id=a.session_id JOIN video_task t ON t.biz_task_id=a.task_id WHERE a.user_id=? AND s.user_id=? AND a.session_id=? AND a.artifact_id=? AND a.version_no=? AND a.type='IMAGE'",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},owner,owner,session,ref.path("artifactId").asText(),ref.path("version").intValue());
        if(rows.size()!=1) throw BusinessException.notFound("参考图片版本不存在");
        var row=rows.get(0);
        references.validateAvailable(owner,new StoredImageReferences.Reference(row[0],row[2]));
        return json.createObjectNode().put("userId",owner).put("sessionId",session).put("taskId",row[0]).put("objectKey",row[2])
                .put("title",row[1]).put("mediaPath","/api/agent/media/"+row[0]);
    }
    public static void validateShape(JsonNode ref) {
        if(ref==null || !ref.isObject() || ref.size()!=2 || !ref.path("artifactId").isTextual()
                || ref.path("artifactId").asText().isBlank() || ref.path("artifactId").asText().length()>64
                || !ref.path("version").isIntegralNumber() || !ref.path("version").canConvertToInt() || ref.path("version").intValue()<1)
            throw BusinessException.badRequest("参考图片必须是准确作品ID和版本");
    }
}
