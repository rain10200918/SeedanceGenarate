package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.exception.BusinessException;

/** Closed JSON documents. Only an exact, whole-response fence is presentation, never free-text extraction. */
final class SkillOutputContract {
    private SkillOutputContract() {}
    static JsonNode parse(ObjectMapper json, String raw, int max) {
        if (raw == null || raw.isBlank()) throw new SkillOutputContractException("DOCUMENT_EMPTY $: 必须返回完整作品JSON对象");
        if (raw.length() > max) throw new SkillOutputContractException("DOCUMENT_LIMIT $: 作品JSON超出" + max + "字符上限");
        String value = raw.strip();
        for (String fence : new String[]{"```", "`"}) {
            String first = value.lines().findFirst().orElse("");
            if ((first.equals(fence) || first.equalsIgnoreCase(fence + "json")) && value.endsWith("\n" + fence)) {
                value = value.substring(first.length(), value.length() - fence.length()).strip();
                break;
            }
        }
        try {
            var node = json.readTree(value);
            if (node == null || !node.isObject()) throw new SkillOutputContractException("DOCUMENT_TYPE $: 必须是唯一JSON对象");
            return node;
        } catch (SkillOutputContractException e) { throw e; }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Parser exception messages may contain source text; expose only the category and location.
            var location = e.getLocation();
            throw new SkillOutputContractException("JSON_SYNTAX $: 必须是合法且唯一JSON对象；位置 "
                    + (location == null ? "未知" : location.getLineNr() + ":" + location.getColumnNr()), e);
        }
    }
    static SkillOutputContractException invalid(BusinessException e) {
        if (e instanceof SkillOutputContractException output) return output;
        return new SkillOutputContractException("DOCUMENT_SCHEMA $: " + e.getMessage(), e);
    }
}
